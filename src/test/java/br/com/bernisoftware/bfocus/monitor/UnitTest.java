package br.com.bernisoftware.bfocus.monitor;

import static br.com.bernisoftware.bfocus.monitor.MiniJson.arr;
import static br.com.bernisoftware.bfocus.monitor.MiniJson.at;
import static br.com.bernisoftware.bfocus.monitor.MiniJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.loja.Pedido;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UnitTest {
    @AfterEach
    void tearDown() {
        BfocusMonitor.close();
    }

    // ---- init ----

    @Test
    void initRequiresKey() {
        assertThrows(IllegalArgumentException.class, () -> BfocusMonitor.init(MonitorOptions.builder().key("  ").build()));
        assertThrows(NullPointerException.class, () -> BfocusMonitor.init(null));
    }

    @Test
    void callsBeforeInitAreNoOps() {
        BfocusMonitor.close();
        BfocusMonitor.captureException(new IllegalStateException("x"));
        BfocusMonitor.captureMessage("x");
        BfocusMonitor.setUser("u", "c");
        BfocusMonitor.setTag("k", "v");
        BfocusMonitor.addBreadcrumb("c", "m");
        assertTrue(BfocusMonitor.flush(Duration.ofMillis(10)));
    }

    @Test
    void initSendsNoEventsAndHeartbeatGoesInBackground() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            assertEquals(1, server.waitHeartbeats(1).size());
            Thread.sleep(200);
            assertTrue(server.requests().isEmpty());
        }
    }

    // ---- sinal de vida ----

    @Test
    void heartbeatRepeatsWithStableInstance() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).heartbeatInterval(Duration.ofMillis(150)).build());
            List<FakeServer.Received> beats = server.waitHeartbeats(3);
            assertTrue(beats.size() >= 3, "esperava 3 heartbeats, vieram " + beats.size());
            Set<Object> instances = new HashSet<>();
            for (FakeServer.Received b : beats) instances.add(obj(MiniJson.parse(b.body)).get("instance"));
            assertEquals(1, instances.size());
            String instance = (String) instances.iterator().next();
            assertEquals(MonitorClient.instance(), instance);
            assertTrue(instance.matches("[0-9a-f]{16}"), instance);
            assertEquals(MonitorClient.hostName(), obj(MiniJson.parse(beats.get(0).body)).get("host"));

            BfocusMonitor.close(); // close para o agendador
            int after = server.heartbeats().size();
            Thread.sleep(500);
            assertEquals(after, server.heartbeats().size());
        }
    }

    @Test
    void heartbeat401DisablesSending() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.heartbeatRespond(401);
            BfocusMonitor.init(TestSupport.options(server).heartbeatInterval(Duration.ofMillis(100)).build());
            server.waitHeartbeats(1);
            long until = System.nanoTime() + 3_000_000_000L;
            while (!BfocusMonitor.current().isDisabled() && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(BfocusMonitor.current().isDisabled());
            BfocusMonitor.captureMessage("não sai");
            BfocusMonitor.flush(Duration.ofSeconds(1));
            Thread.sleep(300);
            assertTrue(server.requests().isEmpty());
            assertEquals(1, server.heartbeats().size()); // desligado: o agendador também para de bater
        }
    }

    @Test
    void heartbeatNetworkFailureIsIgnored() throws Exception {
        BfocusMonitor.init(MonitorOptions.builder().key("bf_mon_x").baseUrl("http://127.0.0.1:1").autoCapture(false)
            .heartbeatInterval(Duration.ofMillis(50)).build());
        Thread.sleep(300);
        assertFalse(BfocusMonitor.current().isDisabled());
    }

    @Test
    void flushAndCloseDoNotSendHeartbeat() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            server.waitHeartbeats(1);
            Thread.sleep(100);
            BfocusMonitor.flush(Duration.ofSeconds(1));
            BfocusMonitor.close();
            Thread.sleep(200);
            assertEquals(1, server.heartbeats().size());
        }
    }

    // ---- envio ----

    @Test
    void headersAndBody() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.captureException(TestSupport.thrown("Boom", "x"));
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertEquals(1, server.requests().size());
            FakeServer.Received req = server.requests().get(0);
            assertEquals("bfocus-monitor-java/0.1.0", req.header("X-bFocus-Client"));
            assertEquals("bfocus-monitor-java/0.1.0", req.header("User-Agent"));
            assertEquals("application/json", req.header("Content-Type"));
            assertEquals("/api/v1/monitor/events", req.path); // barra final do baseUrl não duplica
            Map<String, Object> ev = obj(req.events().get(0));
            assertEquals("production", ev.get("environment"));
            assertEquals("java", at(ev, "contexts.runtime.name"));
            assertNull(ev.get("user"));
            assertNull(ev.get("transaction"));
        }
    }

    @Test
    void batchGroupsEventsOfTheSameSecond() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).batchInterval(Duration.ofMillis(400)).build());
            for (int i = 0; i < 5; i++) BfocusMonitor.captureMessage("lote " + i, Level.WARNING);
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertEquals(1, server.requests().size());
            assertEquals(5, server.requests().get(0).events().size());
        }
    }

    @Test
    void serverErrorRetriesOnceThenDropsWithoutDisabling() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.respond(500, "{}").respond(503, "{}");
            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.captureMessage("cai");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertEquals(2, server.requests().size());
            assertFalse(BfocusMonitor.current().isDisabled());
            BfocusMonitor.captureMessage("depois");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertEquals(3, server.requests().size());
        }
    }

    @Test
    void networkErrorRetriesOnceAndNeverThrows() {
        BfocusMonitor.init(MonitorOptions.builder().key("bf_mon_x").baseUrl("http://127.0.0.1:1").autoCapture(false)
            .retryDelay(Duration.ofMillis(10)).build());
        BfocusMonitor.captureMessage("sem rede");
        assertTrue(BfocusMonitor.flush(Duration.ofSeconds(10)));
    }

    @Test
    void forbiddenDisablesUntilNextInit() throws Exception {
        try (FakeServer server = new FakeServer()) {
            server.respond(403, "{\"error\":\"MONITOR_AGENT_DISABLED\"}");
            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.captureMessage("um");
            BfocusMonitor.flush(Duration.ofSeconds(5));
            assertTrue(BfocusMonitor.current().isDisabled());
            BfocusMonitor.captureMessage("dois");
            BfocusMonitor.flush(Duration.ofSeconds(1));
            assertEquals(1, server.requests().size());

            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.captureMessage("três");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertEquals(2, server.requests().size());
        }
    }

    @Test
    void rateLimit100PerMinute() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            for (int i = 0; i < 150; i++) BfocusMonitor.captureMessage("distinto " + i);
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(10)));
            assertEquals(100, server.allEvents().size());
            for (FakeServer.Received r : server.requests()) assertTrue(r.events().size() <= Transport.MAX_BATCH);
        }
    }

    @Test
    void queueIsBoundedDropsNewest() {
        Transport t = new Transport("http://127.0.0.1:1", "k", Duration.ZERO, Duration.ofHours(1));
        t.holdForTest = true;
        try {
            int accepted = 0;
            for (int i = 0; i < 300; i++) if (t.enqueue("{}")) accepted++;
            assertEquals(Transport.MAX_QUEUE, accepted);
            assertEquals(Transport.MAX_QUEUE, t.queueCount());
            assertFalse(t.flush(Duration.ofMillis(50))); // segura: o teto do flush vale
        } finally {
            t.stop();
        }
    }

    // ---- evento ----

    @Test
    void chainedExceptionSendsRootCause() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            IllegalStateException wrapped = assertThrows(IllegalStateException.class, Pedido::fecharComErroEncadeado);
            BfocusMonitor.captureException(wrapped);
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            Map<String, Object> ev = server.allEvents().get(0);
            assertEquals("java.lang.ArithmeticException", at(ev, "exception.type"));
            assertEquals("/ by zero (dentro de: java.lang.IllegalStateException: Falha ao fechar o pedido)", at(ev, "exception.message"));
            List<Object> frames = arr(at(ev, "exception.frames"));
            Map<String, Object> last = obj(frames.get(frames.size() - 1));
            assertEquals("Pedido.calcular", last.get("function"));
            assertEquals("com/acme/loja/Pedido.java", last.get("file"));
            assertEquals(Boolean.TRUE, last.get("inApp"));
        }
    }

    @Test
    void chainedExceptionWhoseMessageContainsTheCauseKeepsOnlyOuterType() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.captureException(new RuntimeException(new java.io.IOException("conexão caiu")));
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            Map<String, Object> ev = server.allEvents().get(0);
            assertEquals("java.io.IOException", at(ev, "exception.type"));
            assertEquals("conexão caiu (dentro de: java.lang.RuntimeException)", at(ev, "exception.message"));
        }
    }

    @Test
    void realStackTraceOutsideInWithInApp() {
        ArithmeticException ex = assertThrows(ArithmeticException.class, () -> Pedido.fechar(0));
        List<MonitorEvent.Frame> frames = StackFrames.fromThrowable(ex, new ArrayList<>());
        assertTrue(frames.size() >= 3);
        MonitorEvent.Frame last = frames.get(frames.size() - 1);
        MonitorEvent.Frame previous = frames.get(frames.size() - 2);
        assertEquals("Pedido.calcular", last.getFunction());
        assertEquals("Pedido.fechar", previous.getFunction());
        assertTrue(last.isInApp());
        assertTrue(last.getLine() != null && last.getLine() > 0);
        // frames do JUnit/JDK (mais externos) não são do sistema
        assertFalse(frames.get(0).isInApp());
    }

    @Test
    void inAppHeuristic() {
        List<String> none = new ArrayList<>();
        assertFalse(StackFrames.isInApp("java.util.ArrayList", none));
        assertFalse(StackFrames.isInApp("jdk.internal.reflect.X", none));
        assertFalse(StackFrames.isInApp("org.springframework.web.servlet.DispatcherServlet", none));
        assertFalse(StackFrames.isInApp("kotlin.collections.X", none));
        assertFalse(StackFrames.isInApp("br.com.bernisoftware.bfocus.monitor.BfocusMonitor", none));
        assertTrue(StackFrames.isInApp("com.acme.loja.Pedido", none));
        assertTrue(StackFrames.isInApp("org.springframework.acme.Meu", List.of("org.springframework.acme.")));
        assertFalse(StackFrames.isInApp("br.com.bernisoftware.bfocus.monitor.X", List.of("br.com.")));
        assertEquals("com/acme/Pedido.java", StackFrames.fileOf("com.acme.Pedido$1", "Pedido.java"));
        assertEquals("Main.java", StackFrames.fileOf("Main", "Main.java"));
        assertNull(StackFrames.fileOf("com.acme.Pedido", null));
    }

    @Test
    void ignoreSampleBeforeSend() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server)
                .ignore(Pattern.compile("^ruído \\d+$"))
                .beforeSend(e -> {
                    if ("descartar".equals(e.getException().getMessage())) return null;
                    if ("explode".equals(e.getException().getMessage())) throw new IllegalStateException("bug no beforeSend");
                    e.getTags().put("alterado", "sim");
                    return e;
                }).build());
            BfocusMonitor.captureMessage("ruído 42");
            BfocusMonitor.captureMessage("descartar");
            BfocusMonitor.captureMessage("explode");
            BfocusMonitor.captureMessage("fica");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            List<Map<String, Object>> events = server.allEvents();
            assertEquals(2, events.size());
            assertEquals("explode", at(events.get(0), "exception.message"));
            assertNull(events.get(0).get("tags")); // beforeSend que lança: vai como estava
            assertEquals("sim", at(events.get(1), "tags.alterado"));

            BfocusMonitor.init(TestSupport.options(server).sampleRate(0).build());
            int before = server.requests().size();
            BfocusMonitor.captureMessage("amostra zero");
            BfocusMonitor.flush(Duration.ofSeconds(1));
            assertEquals(before, server.requests().size());
        }
    }

    @Test
    void signatureIsRenewedAfter6Days() throws Exception {
        try (FakeServer server = new FakeServer()) {
            AtomicLong now = new AtomicLong(1760000000L);
            BfocusMonitor.init(TestSupport.options(server).signingSecret("whs_secret_A").signingClock(now::get).build());
            BfocusMonitor.setUser("u-123", "cliente-9");
            BfocusMonitor.captureMessage("primeiro");
            now.addAndGet(3600); // 1 h: mantém
            BfocusMonitor.captureMessage("segundo");
            now.set(1760000000L + Signing.MAX_AGE_SECONDS + 1); // passou de 6 dias: assina de novo
            BfocusMonitor.captureMessage("terceiro");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            List<Map<String, Object>> events = server.allEvents();
            assertEquals(Signing.userHash("whs_secret_A", 1760000000L, "u-123", "cliente-9"), at(events.get(0), "user.userHash"));
            assertEquals(at(events.get(0), "user.userHash"), at(events.get(1), "user.userHash"));
            assertEquals(Signing.userHash("whs_secret_A", now.get(), "u-123", "cliente-9"), at(events.get(2), "user.userHash"));
        }
    }

    @Test
    void scopeIsolatesIdentityBetweenConcurrentRequests() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            BfocusMonitor.setTag("global", "1");
            CyclicBarrier gate = new CyclicBarrier(2);
            List<Thread> threads = new ArrayList<>();
            for (String who : List.of("ana", "bia")) {
                Thread t = new Thread(() -> {
                    try (MonitorScope scope = BfocusMonitor.beginScope("GET /pedidos", "https://loja.test/pedidos?cpf=123")) {
                        BfocusMonitor.setUser(who, "cliente-" + who);
                        BfocusMonitor.setTag("quem", who);
                        gate.await();
                        BfocusMonitor.captureMessage("erro de " + who);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    assertNull(MonitorScope.current()); // thread limpa
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) t.join();
            BfocusMonitor.captureMessage("fora de requisição");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            List<Map<String, Object>> events = server.allEvents();
            assertEquals(3, events.size());
            for (String who : List.of("ana", "bia")) {
                Map<String, Object> ev = null;
                for (Map<String, Object> e : events) if (("erro de " + who).equals(at(e, "exception.message"))) ev = e;
                assertNotNull(ev);
                assertEquals(who, at(ev, "user.externalId"));
                assertEquals("cliente-" + who, at(ev, "customer.externalId"));
                assertEquals(who, at(ev, "tags.quem"));
                assertEquals("1", at(ev, "tags.global"));
                assertEquals("https://loja.test/pedidos", ev.get("url"));
                assertEquals("GET /pedidos", ev.get("transaction"));
            }
            Map<String, Object> outside = null;
            for (Map<String, Object> e : events) if ("fora de requisição".equals(at(e, "exception.message"))) outside = e;
            assertNotNull(outside);
            assertNull(outside.get("user"));
            assertNull(outside.get("url"));
        }
    }

    @Test
    void breadcrumbsKeepTheLast30() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            for (int i = 0; i < 40; i++) BfocusMonitor.addBreadcrumb("passo", "p" + i);
            BfocusMonitor.captureMessage("com passos");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            List<Object> crumbs = arr(server.allEvents().get(0).get("breadcrumbs"));
            assertEquals(30, crumbs.size());
            assertEquals("p39", obj(crumbs.get(29)).get("message"));
            assertEquals("info", obj(crumbs.get(0)).get("level"));
        }
    }

    @Test
    void eventIsCutTo64KB() {
        MonitorEvent ev = new MonitorEvent();
        ev.setTimestamp("2026-10-06T12:00:00.000Z");
        ev.getException().setType("X");
        ev.getException().setMessage("m".repeat(2000));
        List<MonitorEvent.Frame> frames = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            MonitorEvent.Frame f = new MonitorEvent.Frame();
            f.setFile("f".repeat(2000));
            f.setFunction("F" + i);
            f.setLine(i + 1);
            f.setInApp(true);
            frames.add(f);
        }
        ev.getException().setFrames(frames);
        String json = MonitorClient.fit(ev);
        assertNotNull(json);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= MonitorClient.MAX_EVENT_BYTES);
        List<Object> out = arr(at(MiniJson.parse(json), "exception.frames"));
        assertEquals("F59", obj(out.get(out.size() - 1)).get("function")); // o frame onde estourou fica
    }

    @Test
    void jsonEscapesAndOmitsNulls() {
        MonitorEvent ev = new MonitorEvent();
        ev.setTimestamp("t");
        ev.getException().setType("T");
        ev.getException().setMessage("aspas \" barra \\ linha\n ctrl \u0001 ção");
        String json = EventJson.serialize(ev);
        assertEquals("aspas \" barra \\ linha\n ctrl \u0001 ção", at(MiniJson.parse(json), "exception.message"));
        assertFalse(json.contains("null"));
        assertFalse(json.contains("release"));
    }

    @Test
    void uncaughtHandlerCapturesFatalAndChainsTheOriginal() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).autoCapture(true).build());
            List<Throwable> seenByOriginal = new ArrayList<>();
            BfocusMonitor.UncaughtHandler handler = new BfocusMonitor.UncaughtHandler((t, e) -> seenByOriginal.add(e));
            RuntimeException boom = TestSupport.thrown("Fatal1", "morreu");
            handler.uncaughtException(Thread.currentThread(), boom);
            // o handler já fez flush (até 2 s) e passou adiante
            assertEquals(List.of(boom), seenByOriginal);
            Map<String, Object> ev = server.allEvents().get(0);
            assertEquals("fatal", ev.get("level"));
            assertEquals("Fatal1", at(ev, "exception.type"));

            // o gancho global foi instalado e encadeia o que já existia
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() instanceof BfocusMonitor.UncaughtHandler);

            // autoCapture=false: o gancho (já instalado no processo) não captura para este monitor
            BfocusMonitor.init(TestSupport.options(server).autoCapture(false).build());
            handler.uncaughtException(Thread.currentThread(), TestSupport.thrown("Fatal2", "x"));
            BfocusMonitor.flush(Duration.ofSeconds(1));
            assertEquals(1, server.allEvents().size());
            assertEquals(2, seenByOriginal.size());
        }
    }
}
