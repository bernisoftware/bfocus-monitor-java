package br.com.bernisoftware.bfocus.monitor;

import static br.com.bernisoftware.bfocus.monitor.MiniJson.arr;
import static br.com.bernisoftware.bfocus.monitor.MiniJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Roda TODOS os casos de monitor/conformance/cases.json (cópia em src/test/resources). */
class ConformanceTest {
    static final Map<String, Object> CASES = load();

    static Map<String, Object> load() {
        try (InputStream in = ConformanceTest.class.getResourceAsStream("/cases.json")) {
            return obj(MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<String> sendCases() {
        return arr(CASES.get("send")).stream().map(c -> (String) obj(c).get("name"));
    }

    static Stream<Map<String, Object>> userHashCases() {
        return arr(CASES.get("user_hash")).stream().map(MiniJson::obj);
    }

    @AfterEach
    void tearDown() {
        BfocusMonitor.close();
    }

    @ParameterizedTest
    @MethodSource("userHashCases")
    void userHash(Map<String, Object> v) {
        String got = Signing.userHash((String) v.get("secret"), ((Number) v.get("ts")).longValue(),
            (String) v.get("user_external_id"), (String) v.get("customer_external_id"));
        assertEquals(v.get("expected"), got);
    }

    @Test
    void frames() {
        for (Object c : arr(CASES.get("frames"))) {
            // Rastro neutro: biblioteca vira classe do Spring; o resto, do app.
            List<StackFrames.Raw> raw = new ArrayList<>();
            for (Object f : arr(obj(c).get("runtime_order"))) {
                Map<String, Object> m = obj(f);
                String cls = Boolean.TRUE.equals(m.get("library")) ? "org.springframework.web.servlet.DispatcherServlet" : "com.acme.loja.Main";
                raw.add(new StackFrames.Raw(cls, (String) m.get("function"), (String) m.get("file"), ((Number) m.get("line")).intValue()));
            }
            List<MonitorEvent.Frame> got = StackFrames.convert(raw, new ArrayList<>());
            List<Object> expected = arr(obj(c).get("expected"));
            assertEquals(expected.size(), got.size());
            for (int i = 0; i < expected.size(); i++) {
                Map<String, Object> e = obj(expected.get(i));
                assertEquals(e.get("file"), got.get(i).getFile());
                assertEquals(e.get("function"), got.get(i).getFunction());
                assertEquals(((Number) e.get("line")).intValue(), got.get(i).getLine());
                assertEquals(e.get("inApp"), got.get(i).isInApp());
            }
        }
    }

    static Stream<String> heartbeatCases() {
        return arr(CASES.get("heartbeat")).stream().map(c -> (String) obj(c).get("name"));
    }

    @ParameterizedTest
    @MethodSource("heartbeatCases")
    void heartbeat(String name) throws Exception {
        Map<String, Object> c = null;
        for (Object x : arr(CASES.get("heartbeat"))) if (name.equals(obj(x).get("name"))) c = obj(x);
        assertNotNull(c);
        try (FakeServer server = new FakeServer()) {
            server.heartbeatRespond(((Number) obj(c.get("respond")).get("status")).intValue());
            Map<String, Object> init = obj(c.get("init"));
            BfocusMonitor.init(MonitorOptions.builder()
                .key((String) init.get("key"))
                .release((String) init.get("release"))
                .environment((String) init.get("environment"))
                .baseUrl(server.baseUrl())
                .autoCapture(false)
                .build());

            List<FakeServer.Received> beats = server.waitHeartbeats(1);
            assertEquals(1, beats.size());
            FakeServer.Received got = beats.get(0);
            Map<String, Object> expect = obj(c.get("expect"));
            assertEquals(expect.get("method"), got.method);
            assertEquals(expect.get("path"), got.path);
            for (Map.Entry<String, Object> h : obj(expect.get("headers")).entrySet()) assertEquals(h.getValue(), got.header(h.getKey()));
            for (Map.Entry<String, Object> h : obj(expect.get("header_prefix")).entrySet()) {
                String v = got.header(h.getKey());
                assertTrue(v != null && v.startsWith((String) h.getValue()), h.getKey() + ": " + v);
            }
            assertEquals("bfocus-monitor-java/" + BfocusMonitor.VERSION, got.header("X-bFocus-Client"));

            Map<String, Object> body = obj(MiniJson.parse(got.body));
            assertNoNulls(body);
            for (Map.Entry<String, Object> f : obj(expect.get("body")).entrySet()) {
                Object want = "$version".equals(f.getValue()) ? BfocusMonitor.VERSION : f.getValue();
                Object actual = MiniJson.at(body, f.getKey());
                assertTrue(MiniJson.same(want, actual), f.getKey() + ": esperado " + want + ", veio " + actual);
            }
            for (Object path : arr(expect.get("body_present"))) {
                Object v = MiniJson.at(body, (String) path);
                assertTrue(v != null && !"".equals(v), "heartbeat sem " + path + ": " + got.body);
            }
            assertEquals("bfocus-monitor-java", MiniJson.at(body, "sdk.name"));
            assertEquals("java", MiniJson.at(body, "runtime.name"));
            assertFalse(BfocusMonitor.current().isDisabled());
            assertTrue(server.requests().isEmpty()); // heartbeat não é evento
        }
    }

    @ParameterizedTest
    @MethodSource("sendCases")
    void send(String name) throws Exception {
        Map<String, Object> c = null;
        for (Object x : arr(CASES.get("send"))) if (name.equals(obj(x).get("name"))) c = obj(x);
        assertNotNull(c);
        List<Object> expectedRequests = arr(c.get("requests"));
        try (FakeServer server = new FakeServer()) {
            for (Object r : expectedRequests) {
                Map<String, Object> respond = obj(obj(r).get("respond"));
                server.respond(((Number) respond.get("status")).intValue(), toJson(respond.get("body")));
            }
            Map<String, Object> init = obj(c.get("init"));
            MonitorOptions.Builder b = MonitorOptions.builder()
                .key((String) init.get("key"))
                .release((String) init.get("release"))
                .environment((String) init.get("environment"))
                .baseUrl(server.baseUrl())
                .signingSecret((String) init.get("signing_secret"))
                .autoCapture(false)
                .retryDelay(Duration.ofMillis(50))
                .batchInterval(Duration.ofMillis(100));
            if (init.get("ignore") != null) for (Object s : arr(init.get("ignore"))) b.ignore((String) s);
            Map<String, Object> setUser = c.get("set_user") == null ? null : obj(c.get("set_user"));
            if (setUser != null && setUser.get("ts") != null) {
                long ts = ((Number) setUser.get("ts")).longValue();
                b.signingClock(() -> ts);
            }
            BfocusMonitor.init(b.build());

            if (setUser != null) {
                BfocusMonitor.setUser((String) setUser.get("user_external_id"), (String) setUser.get("customer_external_id"),
                    (String) setUser.get("user_hash"));
            }
            if (c.get("breadcrumbs") != null) {
                for (Object x : arr(c.get("breadcrumbs"))) {
                    Map<String, Object> bc = obj(x);
                    BfocusMonitor.addBreadcrumb((String) bc.get("category"), (String) bc.get("message"), TestSupport.level((String) bc.get("level")));
                }
            }
            int repeat = c.get("repeat") == null ? 1 : ((Number) c.get("repeat")).intValue();
            for (int i = 0; i < repeat; i++) capture(obj(c.get("capture")));
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(10)), "flush não esvaziou a fila");

            List<FakeServer.Received> got = server.requests();
            assertEquals(expectedRequests.size(), got.size());
            for (int i = 0; i < expectedRequests.size(); i++) check(obj(obj(expectedRequests.get(i)).get("expect")), got.get(i));
            if (expectedRequests.size() == 2) assertEquals(got.get(0).body, got.get(1).body); // nova tentativa = mesmo corpo

            if (c.get("then_capture") != null) {
                capture(obj(c.get("then_capture")));
                BfocusMonitor.flush(Duration.ofSeconds(5));
            }
            Thread.sleep(250); // nada atrasado chegando
            assertEquals(expectedRequests.size(), server.requests().size());
            assertEquals("disabled".equals(c.get("after")), BfocusMonitor.current().isDisabled());
        }
    }

    private static void check(Map<String, Object> expect, FakeServer.Received got) {
        assertEquals(expect.get("method"), got.method);
        assertEquals(expect.get("path"), got.path);
        for (Map.Entry<String, Object> h : obj(expect.get("headers")).entrySet()) assertEquals(h.getValue(), got.header(h.getKey()));
        for (Map.Entry<String, Object> h : obj(expect.get("header_prefix")).entrySet()) {
            String v = got.header(h.getKey());
            assertTrue(v != null && v.startsWith((String) h.getValue()), h.getKey() + ": " + v);
        }
        assertEquals("bfocus-monitor-java/" + BfocusMonitor.VERSION, got.header("X-bFocus-Client"));

        List<Object> events = got.events();
        assertEquals(1, events.size());
        Map<String, Object> ev = obj(events.get(0));
        assertNoNulls(ev);
        assertTrue(((String) ev.get("timestamp")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"), (String) ev.get("timestamp"));
        assertEquals("bfocus-monitor-java", MiniJson.at(ev, "sdk.name"));
        for (Map.Entry<String, Object> f : obj(expect.get("event")).entrySet()) {
            Object want = "$version".equals(f.getValue()) ? BfocusMonitor.VERSION : f.getValue();
            Object actual = MiniJson.at(ev, f.getKey());
            assertNotNull(actual, "evento sem o campo " + f.getKey() + ": " + got.body);
            assertTrue(MiniJson.same(want, actual), f.getKey() + ": esperado " + want + ", veio " + actual);
        }
    }

    static void assertNoNulls(Object node) {
        assertNotNull(node);
        if (node instanceof Map) for (Object v : obj(node).values()) assertNoNulls(v);
        if (node instanceof List) for (Object v : arr(node)) assertNoNulls(v);
    }

    private static void capture(Map<String, Object> cap) {
        Level level = TestSupport.level((String) cap.get("level"));
        if ("message".equals(cap.get("kind"))) {
            BfocusMonitor.captureMessage((String) cap.get("message"), level == null ? Level.INFO : level);
            return;
        }
        RuntimeException error = TestSupport.thrown((String) cap.get("type"), (String) cap.get("message"));
        Map<String, String> tags = null;
        if (cap.get("tags") != null) {
            tags = new LinkedHashMap<>();
            for (Map.Entry<String, Object> t : obj(cap.get("tags")).entrySet()) tags.put(t.getKey(), (String) t.getValue());
        }
        List<String> fingerprint = null;
        if (cap.get("fingerprint") != null) {
            fingerprint = new ArrayList<>();
            for (Object s : arr(cap.get("fingerprint"))) fingerprint.add((String) s);
        }
        BfocusMonitor.captureException(error, level == null ? Level.ERROR : level, tags, fingerprint);
    }

    /** Corpo de resposta do caso de volta a JSON (o servidor falso só repete). */
    static String toJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            StringBuilder sb = new StringBuilder();
            EventJson.quote(sb, (String) v);
            return sb.toString();
        }
        if (v instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : obj(v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(e.getKey())).append(':').append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : arr(v)) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(e));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    @Test
    void vendoredCasesMatchMonorepo() throws IOException {
        java.nio.file.Path mono = TestSupport.projectDir().resolve("../conformance/cases.json").normalize();
        if (!java.nio.file.Files.exists(mono)) return; // espelho público
        java.nio.file.Path local = TestSupport.projectDir().resolve("src/test/resources/cases.json");
        assertEquals(java.nio.file.Files.readString(mono), java.nio.file.Files.readString(local));
        assertFalse(java.nio.file.Files.readString(local).isEmpty());
    }
}
