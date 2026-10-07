package br.com.bernisoftware.bfocus.monitor;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * Servidor HTTP local: grava o que chega e responde o roteiro (depois dele, 202). Eventos e sinais de vida ficam
 * separados: o roteiro de eventos não é consumido pelo heartbeat (que responde 204).
 */
final class FakeServer implements AutoCloseable {
    static final class Received {
        final String method;
        final String path;
        final Map<String, String> headers;
        final String body;

        Received(String method, String path, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name);
        }

        List<Object> events() {
            return MiniJson.arr(MiniJson.obj(MiniJson.parse(body)).get("events"));
        }
    }

    private final HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final Deque<Object[]> script = new ArrayDeque<>();
    private final List<Received> heartbeats = new ArrayList<>();
    private final Deque<Integer> heartbeatScript = new ArrayDeque<>();
    static final String HEARTBEAT_PATH = "/api/v1/monitor/heartbeat";

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-server");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
            Object[] reply;
            synchronized (received) {
                Received got = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers,
                    new String(in, StandardCharsets.UTF_8));
                if (HEARTBEAT_PATH.equals(got.path)) {
                    heartbeats.add(got);
                    reply = new Object[] {heartbeatScript.isEmpty() ? 204 : heartbeatScript.poll(), ""};
                } else {
                    received.add(got);
                    reply = script.isEmpty() ? new Object[] {202, "{\"accepted\":1}"} : script.poll();
                }
            }
            byte[] out = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
            if (out.length == 0) {
                ex.sendResponseHeaders((Integer) reply[0], -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) reply[0], out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    FakeServer respond(int status, String body) {
        synchronized (received) {
            script.add(new Object[] {status, body});
        }
        return this;
    }

    FakeServer heartbeatRespond(int status) {
        synchronized (received) {
            heartbeatScript.add(status);
        }
        return this;
    }

    List<Received> heartbeats() {
        synchronized (received) {
            return new ArrayList<>(heartbeats);
        }
    }

    /** Espera chegar ao menos {@code count} heartbeats (até 5 s). */
    List<Received> waitHeartbeats(int count) throws InterruptedException {
        long until = System.nanoTime() + 5_000_000_000L;
        while (heartbeats().size() < count && System.nanoTime() < until) Thread.sleep(20);
        return heartbeats();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> requests() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    List<Map<String, Object>> allEvents() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Received r : requests()) for (Object e : r.events()) out.add(MiniJson.obj(e));
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
