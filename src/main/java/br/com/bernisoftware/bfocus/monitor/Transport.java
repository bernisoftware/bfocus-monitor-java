package br.com.bernisoftware.bfocus.monitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fila em memória e uma thread daemon que envia em lote (a cada 1 s ou 20 eventos). 429/5xx/rede: uma nova
 * tentativa depois de 2 s. 401/403: desliga o envio até o próximo init. Nunca lança.
 */
final class Transport {
    static final int MAX_QUEUE = 100;
    static final int BATCH_TRIGGER = 20;
    static final int MAX_BATCH = 50;

    private final Object lock = new Object();
    private final List<String> queue = new ArrayList<>();
    private final URI endpoint;
    private final URI heartbeatEndpoint;
    private final String key;
    private final long retryDelayMs;
    private final long intervalMs;
    private HttpClient http;
    private Thread thread;
    private long firstAtNanos;
    private boolean sending;
    private boolean flushRequested;
    private boolean stopped;
    private volatile boolean disabled;
    /** Só para teste: segura a thread (a fila enche sem ninguém consumir). */
    volatile boolean holdForTest;

    Transport(String baseUrl, String key, Duration retryDelay, Duration interval) {
        this.endpoint = URI.create(baseUrl + "/api/v1/monitor/events");
        this.heartbeatEndpoint = URI.create(baseUrl + "/api/v1/monitor/heartbeat");
        this.key = key;
        this.retryDelayMs = retryDelay.toMillis();
        this.intervalMs = interval.toMillis();
    }

    static String clientHeader() {
        return BfocusMonitor.SDK_NAME + "/" + BfocusMonitor.VERSION;
    }

    boolean isDisabled() {
        return disabled;
    }

    int queueCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Põe o evento (JSON) na fila. Cheia: descarta o mais novo (este). */
    boolean enqueue(String eventJson) {
        synchronized (lock) {
            if (stopped || disabled || queue.size() >= MAX_QUEUE) return false;
            if (queue.isEmpty()) firstAtNanos = System.nanoTime();
            queue.add(eventJson);
            ensureThread();
            lock.notifyAll();
            return true;
        }
    }

    /** Envia o que está na fila e espera terminar, até o teto. Devolve se esvaziou. */
    boolean flush(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        synchronized (lock) {
            if (queue.isEmpty() && !sending) return true;
            flushRequested = true;
            ensureThread();
            lock.notifyAll();
            while (!queue.isEmpty() || sending) {
                long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMs <= 0) return false;
                try {
                    lock.wait(leftMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    void stop() {
        synchronized (lock) {
            stopped = true;
            queue.clear();
            lock.notifyAll();
        }
    }

    private void ensureThread() {
        if (thread != null) return;
        thread = new Thread(this::run, "bfocus-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (true) {
            List<String> batch;
            synchronized (lock) {
                try {
                    while (true) {
                        if (stopped) {
                            lock.notifyAll();
                            return;
                        }
                        if (queue.isEmpty() || holdForTest) {
                            if (queue.isEmpty()) flushRequested = false;
                            lock.notifyAll();
                            lock.wait(100);
                            continue;
                        }
                        long waitedMs = (System.nanoTime() - firstAtNanos) / 1_000_000L;
                        long left = intervalMs - waitedMs;
                        if (flushRequested || queue.size() >= BATCH_TRIGGER || left <= 0) break;
                        lock.wait(Math.max(1, left));
                    }
                } catch (InterruptedException e) {
                    thread = null; // o próximo enqueue sobe outra
                    return;
                }
                int n = Math.min(queue.size(), MAX_BATCH);
                batch = new ArrayList<>(queue.subList(0, n));
                queue.subList(0, n).clear();
                if (!queue.isEmpty()) firstAtNanos = System.nanoTime();
                sending = true;
            }
            try {
                send(batch);
            } catch (Throwable ignored) {
                // nunca derruba o app
            }
            synchronized (lock) {
                sending = false;
                lock.notifyAll();
            }
        }
    }

    private void send(List<String> batch) throws InterruptedException {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(batch.get(i));
        }
        sb.append("]}");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (disabled) return;
            int status = post(body);
            if (status >= 200 && status < 300) return;
            if (status == 401 || status == 403) {
                synchronized (lock) {
                    disabled = true;
                    queue.clear();
                }
                return;
            }
            boolean retryable = status == -1 || status == 429 || status >= 500;
            if (!retryable || attempt == 1) return;
            Thread.sleep(retryDelayMs);
        }
    }

    /**
     * Sinal de vida (BRIEF 7b): 204 ok; 401/403 desliga o envio como nos eventos; falha de rede é ignorada
     * (o próximo intervalo tenta de novo). Chamado pela thread do agendador, nunca por quem chamou o init.
     */
    void sendHeartbeat(String json) {
        if (disabled) return;
        synchronized (lock) {
            if (stopped) return;
        }
        int status = post(json.getBytes(StandardCharsets.UTF_8), heartbeatEndpoint);
        if (status == 401 || status == 403) {
            synchronized (lock) {
                disabled = true;
                queue.clear();
            }
        }
    }

    /** Status HTTP, ou -1 em erro de rede. */
    private int post(byte[] body) {
        return post(body, endpoint);
    }

    private synchronized HttpClient http() {
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return http;
    }

    private int post(byte[] body, URI target) {
        try {
            HttpRequest req = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("X-bFocus-Monitor-Key", key)
                .header("X-bFocus-Client", clientHeader())
                .header("User-Agent", clientHeader())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            return http().send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
