package br.com.bernisoftware.bfocus.monitor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Um monitor ligado (um por init). Toda falha interna é engolida. */
final class MonitorClient {
    static final int MAX_BREADCRUMBS = 30;
    static final int MAX_MESSAGE = 2000;
    static final int MAX_EVENT_BYTES = 64 * 1024;
    static final long DEDUPE_MS = 30_000;
    static final int MAX_PER_MINUTE = 100;
    static final int MAX_CHAIN = 10;
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Object gate = new Object();
    private final MonitorOptions options;
    private final Transport transport;
    private final Map<String, Long> seen = new LinkedHashMap<>();
    private final Map<String, String> tags = new LinkedHashMap<>();
    private final List<MonitorEvent.Breadcrumb> crumbs = new ArrayList<>();
    private Identity identity;
    private long windowStartMs = Long.MIN_VALUE;
    private int windowCount;
    private volatile boolean closed;
    private ScheduledExecutorService heartbeat;

    MonitorClient(MonitorOptions options) {
        this.options = options;
        this.transport = new Transport(options.baseUrl(), options.key(), options.retryDelay, options.batchInterval);
    }

    MonitorOptions options() {
        return options;
    }

    Transport transport() {
        return transport;
    }

    boolean isDisabled() {
        return transport.isDisabled();
    }

    private long nowSeconds() {
        return options.signingClock != null ? options.signingClock.getAsLong() : Signing.nowSeconds();
    }

    private static long monoMs() {
        return System.nanoTime() / 1_000_000L;
    }

    // ---- captura ----

    void captureException(Throwable error, Level level, Map<String, String> extraTags, List<String> fingerprint) {
        if (error == null || closed) return;
        try {
            // Encadeada: tipo e mensagem da causa raiz (a mais interna; o grupo é dela) e a externa como
            // contexto: " (dentro de: <TipoExterno>: <msg externa>)".
            Throwable root = error;
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            visited.add(root);
            int depth = 0;
            while (root.getCause() != null && depth < MAX_CHAIN && visited.add(root.getCause())) {
                root = root.getCause();
                depth++;
            }
            String type = root.getClass().getName();
            String message = nz(root.getMessage());
            if (root != error) {
                String outer = nz(error.getMessage());
                String outerType = error.getClass().getName();
                // Mensagem externa que já contém a interna (new RuntimeException(cause) usa cause.toString()): só o tipo.
                message += outer.isEmpty() || (!message.isEmpty() && outer.contains(message))
                    ? " (dentro de: " + outerType + ")"
                    : " (dentro de: " + outerType + ": " + outer + ")";
            }
            List<MonitorEvent.Frame> frames = StackFrames.fromThrowable(root, options.inAppPrefixes());
            if (frames.isEmpty() && root != error) frames = StackFrames.fromThrowable(error, options.inAppPrefixes());
            enqueue(type, message, frames, level == null ? Level.ERROR : level, extraTags, fingerprint);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    void captureMessage(String message, Level level) {
        if (message == null || message.isEmpty() || closed) return;
        try {
            enqueue("Message", message, new ArrayList<>(), level == null ? Level.INFO : level, null, null);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    private void enqueue(String type, String message, List<MonitorEvent.Frame> frames, Level level,
                         Map<String, String> extraTags, List<String> fingerprint) {
        if (closed || transport.isDisabled()) return;
        if (ignored(message)) return;
        if (options.sampleRate() < 1.0 && ThreadLocalRandom.current().nextDouble() >= options.sampleRate()) return;

        // Sem repetir: o mesmo erro 1 vez a cada 30 s; no máximo 100 eventos por minuto.
        MonitorEvent.Frame top = null;
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).isInApp()) {
                top = frames.get(i);
                break;
            }
        }
        if (top == null && !frames.isEmpty()) top = frames.get(frames.size() - 1);
        String dedupeKey = type + "|" + message + "|"
            + (top == null ? "" : top.getFile() + ":" + top.getFunction() + ":" + top.getLine());
        long now = monoMs();
        synchronized (gate) {
            Long last = seen.get(dedupeKey);
            if (last != null && now - last < DEDUPE_MS) return;
            if (windowStartMs == Long.MIN_VALUE || now - windowStartMs >= 60_000) {
                windowStartMs = now;
                windowCount = 0;
            }
            if (windowCount >= MAX_PER_MINUTE) return;
            windowCount++;
            if (seen.size() > 500) {
                for (Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                    if (now - it.next().getValue() >= DEDUPE_MS) it.remove();
                }
            }
            seen.put(dedupeKey, now);
        }

        MonitorEvent ev = build(type, message, frames, level, extraTags, fingerprint);
        if (options.beforeSend() != null) {
            try {
                ev = options.beforeSend().apply(ev);
            } catch (Throwable ignored) {
                // beforeSend com erro: manda como está
            }
            if (ev == null) return;
        }
        String json = fit(ev);
        if (json != null) transport.enqueue(json);
    }

    private boolean ignored(String message) {
        for (String s : options.ignore()) if (message.contains(s)) return true;
        for (Pattern p : options.ignorePatterns()) {
            try {
                if (p.matcher(message).find()) return true;
            } catch (RuntimeException ignored) {
                // expressão problemática: não ignora
            }
        }
        return false;
    }

    private MonitorEvent build(String type, String message, List<MonitorEvent.Frame> frames, Level level,
                               Map<String, String> extraTags, List<String> fingerprint) {
        MonitorScope scope = MonitorScope.current();
        MonitorEvent ev = new MonitorEvent();
        ev.setTimestamp(iso(Instant.now()));
        ev.setLevel(level);
        ev.setRelease(options.release() == null || options.release().isEmpty() ? null : options.release());
        ev.setEnvironment(options.environment());
        MonitorEvent.ExceptionInfo x = new MonitorEvent.ExceptionInfo();
        x.setType(type);
        x.setMessage(cut(message, MAX_MESSAGE));
        x.setFrames(frames);
        ev.setException(x);
        ev.setContexts(contexts());

        Identity id;
        List<MonitorEvent.Breadcrumb> allCrumbs;
        synchronized (gate) {
            ev.getTags().putAll(tags);
            allCrumbs = new ArrayList<>(crumbs);
            id = identity;
        }
        if (scope != null) {
            synchronized (scope.gate) {
                ev.getTags().putAll(scope.tags);
                allCrumbs.addAll(scope.breadcrumbs);
                if (scope.identity != null) id = scope.identity;
            }
            ev.setTransaction(scope.transaction);
            ev.setUrl(scope.url);
        }
        if (allCrumbs.size() > MAX_BREADCRUMBS) {
            allCrumbs.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            allCrumbs = new ArrayList<>(allCrumbs.subList(allCrumbs.size() - MAX_BREADCRUMBS, allCrumbs.size()));
        }
        ev.setBreadcrumbs(allCrumbs);
        if (extraTags != null) {
            for (Map.Entry<String, String> kv : extraTags.entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null) ev.getTags().put(cut(kv.getKey(), 64), cut(kv.getValue(), 200));
            }
        }
        if (fingerprint != null) {
            List<String> fp = new ArrayList<>();
            for (String s : fingerprint) if (s != null && !s.isEmpty() && fp.size() < 10) fp.add(s);
            if (!fp.isEmpty()) ev.setFingerprint(fp);
        }
        if (id != null) {
            if (id.userExternalId != null) {
                ev.setUser(new MonitorEvent.User(id.userExternalId, id.givenHash != null ? id.givenHash : currentSignature(id)));
            }
            if (id.customerExternalId != null) ev.setCustomer(new MonitorEvent.Customer(id.customerExternalId));
        }
        return ev;
    }

    private String currentSignature(Identity id) {
        synchronized (id) {
            if (id.signedHash == null) return null;
            long now = nowSeconds();
            if (now - id.signedAt > Signing.MAX_AGE_SECONDS && options.signingSecret() != null) {
                id.signedHash = Signing.userHash(options.signingSecret(), now, id.userExternalId, id.customerExternalId);
                id.signedAt = now;
            }
            return id.signedHash;
        }
    }

    /** JSON do evento com no máximo 64 KB (corta mensagem, frames e passos antes de desistir). */
    static String fit(MonitorEvent ev) {
        String json = EventJson.serialize(ev);
        if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_EVENT_BYTES) return json;
        int[][] steps = {{500, 30, 10}, {200, 10, 0}, {100, 3, 0}};
        for (int[] step : steps) {
            MonitorEvent.ExceptionInfo x = ev.getException();
            x.setMessage(cut(nz(x.getMessage()), step[0]));
            List<MonitorEvent.Frame> fr = x.getFrames();
            if (fr.size() > step[1]) x.setFrames(new ArrayList<>(fr.subList(fr.size() - step[1], fr.size())));
            List<MonitorEvent.Breadcrumb> bc = ev.getBreadcrumbs();
            if (bc != null && bc.size() > step[2]) ev.setBreadcrumbs(new ArrayList<>(bc.subList(bc.size() - step[2], bc.size())));
            for (MonitorEvent.Frame f : x.getFrames()) {
                if (f.getFile() != null) f.setFile(cut(f.getFile(), 300));
                if (f.getFunction() != null) f.setFunction(cut(f.getFunction(), 200));
            }
            json = EventJson.serialize(ev);
            if (json.getBytes(StandardCharsets.UTF_8).length <= MAX_EVENT_BYTES) return json;
        }
        return null;
    }

    // ---- contexto ----

    void setUser(String userExternalId, String customerExternalId, String userHash) {
        try {
            Identity id = null;
            if (notEmpty(userExternalId) || notEmpty(customerExternalId)) {
                id = new Identity();
                id.userExternalId = notEmpty(userExternalId) ? userExternalId : null;
                id.customerExternalId = notEmpty(customerExternalId) ? customerExternalId : null;
                id.givenHash = notEmpty(userHash) ? userHash : null;
                if (id.givenHash == null && options.signingSecret() != null
                    && id.userExternalId != null && id.customerExternalId != null) {
                    id.signedAt = nowSeconds();
                    id.signedHash = Signing.userHash(options.signingSecret(), id.signedAt, id.userExternalId, id.customerExternalId);
                }
            }
            MonitorScope scope = MonitorScope.current();
            if (scope != null) {
                synchronized (scope.gate) {
                    scope.identity = id;
                }
            } else {
                synchronized (gate) {
                    identity = id;
                }
            }
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    void setTag(String key, String value) {
        if (!notEmpty(key) || value == null) return;
        String k = cut(key, 64);
        String v = cut(value, 200);
        MonitorScope scope = MonitorScope.current();
        if (scope != null) {
            synchronized (scope.gate) {
                scope.tags.put(k, v);
            }
        } else {
            synchronized (gate) {
                tags.put(k, v);
            }
        }
    }

    void addBreadcrumb(String category, String message, Level level) {
        if (!notEmpty(category) && !notEmpty(message)) return;
        MonitorEvent.Breadcrumb crumb = new MonitorEvent.Breadcrumb(iso(Instant.now()), cut(nz(category), 40),
            cut(nz(message), 300), level == null ? Level.INFO : level);
        MonitorScope scope = MonitorScope.current();
        Object lock = scope != null ? scope.gate : gate;
        List<MonitorEvent.Breadcrumb> list = scope != null ? scope.breadcrumbs : crumbs;
        synchronized (lock) {
            list.add(crumb);
            if (list.size() > MAX_BREADCRUMBS) list.remove(0);
        }
    }

    boolean flush(Duration timeout) {
        try {
            return transport.flush(timeout);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---- sinal de vida ----

    /**
     * Primeiro sinal de vida logo depois do init (na thread daemon do agendador, nunca em quem chamou) e
     * depois a cada 5 min. Thread daemon: não segura o processo vivo.
     */
    void startHeartbeat() {
        try {
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bfocus-monitor-heartbeat");
                t.setDaemon(true);
                return t;
            });
            long every = Math.max(1, options.heartbeatInterval.toMillis());
            ex.scheduleWithFixedDelay(this::beat, 0, every, TimeUnit.MILLISECONDS);
            heartbeat = ex;
        } catch (RuntimeException ignored) {
            // sem agendador: segue sem sinal de vida
        }
    }

    private void beat() {
        try {
            if (closed || transport.isDisabled()) return;
            transport.sendHeartbeat(heartbeatJson());
        } catch (Throwable ignored) {
            // nunca derruba o app (e não mata o agendamento)
        }
    }

    String heartbeatJson() {
        StringBuilder sb = new StringBuilder(256).append('{');
        field(sb, "instance", instance());
        field(sb, "release", options.release() == null || options.release().isEmpty() ? null : options.release());
        field(sb, "environment", options.environment());
        field(sb, "host", hostName());
        sb.append(",\"runtime\":{\"name\":\"java\"");
        String version = System.getProperty("java.version");
        if (version != null) {
            sb.append(",\"version\":");
            EventJson.quote(sb, version);
        }
        sb.append("},\"sdk\":{\"name\":");
        EventJson.quote(sb, BfocusMonitor.SDK_NAME);
        sb.append(",\"version\":");
        EventJson.quote(sb, BfocusMonitor.VERSION);
        return sb.append("}}").toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        if (value == null) return;
        if (sb.length() > 1) sb.append(',');
        EventJson.quote(sb, name);
        sb.append(':');
        EventJson.quote(sb, value);
    }

    static String hostName() {
        try {
            String h = System.getenv("HOSTNAME");
            if (h == null || h.isEmpty()) h = java.net.InetAddress.getLocalHost().getHostName();
            return h == null || h.isEmpty() ? null : h;
        } catch (Exception e) {
            return null;
        }
    }

    /** Id estável do processo: hash curto de hostname + pid. */
    static String instance() {
        long pid;
        try {
            pid = ProcessHandle.current().pid();
        } catch (RuntimeException e) {
            pid = 0;
        }
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            String host = hostName();
            byte[] d = sha.digest(((host == null ? "" : host) + ":" + pid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i] & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Long.toHexString(pid);
        }
    }

    void close(Duration timeout) {
        if (closed) return;
        try {
            if (heartbeat != null) heartbeat.shutdownNow();
        } catch (RuntimeException ignored) {
            // segue fechando
        }
        try {
            transport.flush(timeout);
        } catch (Throwable ignored) {
            // segue fechando
        }
        closed = true;
        transport.stop();
    }

    // ---- utilitários ----

    static String iso(Instant instant) {
        return ISO.format(instant);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Map<String, String>> contexts() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try {
            Map<String, String> runtime = new LinkedHashMap<>();
            runtime.put("name", "java");
            putIf(runtime, "version", System.getProperty("java.version"));
            putIf(runtime, "vendor", System.getProperty("java.vendor"));
            out.put("runtime", runtime);
            Map<String, String> os = new LinkedHashMap<>();
            putIf(os, "name", System.getProperty("os.name"));
            putIf(os, "version", System.getProperty("os.version"));
            putIf(os, "arch", System.getProperty("os.arch"));
            out.put("os", os);
        } catch (RuntimeException ignored) {
            // sem contexto (SecurityManager)
        }
        return out;
    }

    private static void putIf(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) map.put(key, value);
    }
}
