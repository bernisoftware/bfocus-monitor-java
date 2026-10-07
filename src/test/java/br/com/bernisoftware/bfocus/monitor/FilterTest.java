package br.com.bernisoftware.bfocus.monitor;

import static br.com.bernisoftware.bfocus.monitor.MiniJson.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FilterTest {
    @AfterEach
    void tearDown() {
        BfocusMonitor.close();
    }

    private static HttpServletRequest request(String method, String uri, String query) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(FilterTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "getMethod": return method;
                    case "getRequestURI": return uri;
                    case "getRequestURL": return new StringBuffer("https://loja.test" + uri);
                    case "getQueryString": return query;
                    case "setAttribute": attributes.put((String) args[0], args[1]); return null;
                    case "getAttribute": return attributes.get((String) args[0]);
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return null;
                }
            });
    }

    private static ServletResponse response() {
        return (ServletResponse) Proxy.newProxyInstance(FilterTest.class.getClassLoader(),
            new Class<?>[] {ServletResponse.class}, (proxy, m, args) -> null);
    }

    @Test
    void capturesAndRethrowsWithTransactionAndUrlWithoutQuery() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            IllegalStateException boom = new IllegalStateException("estoque negativo");
            FilterChain chain = (req, res) -> {
                BfocusMonitor.setUser("u-1", "c-1", "v2.1.abc");
                throw boom;
            };
            HttpServletRequest req = request("POST", "/pedidos/42", "cpf=12345678900&token=x");
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new BfocusMonitorFilter().doFilter(req, response(), chain));
            assertSame(boom, thrown);
            assertNull(MonitorScope.current()); // ThreadLocal limpo antes de a thread voltar ao pool
            assertTrue(req.getAttribute(BfocusMonitorFilter.SCOPE_ATTRIBUTE) instanceof MonitorScope);

            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            Map<String, Object> ev = server.allEvents().get(0);
            assertEquals("POST /pedidos/42", ev.get("transaction"));
            assertEquals("https://loja.test/pedidos/42", ev.get("url"));
            assertEquals("java.lang.IllegalStateException", at(ev, "exception.type"));
            assertEquals("u-1", at(ev, "user.externalId"));
            assertEquals("v2.1.abc", at(ev, "user.userHash"));
            assertFalse(server.requests().get(0).body.contains("cpf"));

            // a identidade era só daquela requisição
            BfocusMonitor.captureMessage("depois");
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            assertNull(server.allEvents().get(1).get("user"));
        }
    }

    @Test
    void servletExceptionGoesWithTheRootCause() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            FilterChain chain = (req, res) -> {
                throw new ServletException("Request processing failed", new ArithmeticException("/ by zero"));
            };
            assertThrows(ServletException.class,
                () -> new BfocusMonitorFilter().doFilter(request("GET", "/total", null), response(), chain));
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(5)));
            Map<String, Object> ev = server.allEvents().get(0);
            assertEquals("java.lang.ArithmeticException", at(ev, "exception.type"));
            assertEquals("/ by zero (dentro de: jakarta.servlet.ServletException: Request processing failed)", at(ev, "exception.message"));
        }
    }

    @Test
    void successfulRequestSendsNothing() throws Exception {
        try (FakeServer server = new FakeServer()) {
            BfocusMonitor.init(TestSupport.options(server).build());
            new BfocusMonitorFilter().doFilter(request("GET", "/ok", null), response(), (req, res) -> { });
            assertTrue(BfocusMonitor.flush(Duration.ofSeconds(2)));
            assertTrue(server.requests().isEmpty());
            assertNull(MonitorScope.current());
        }
    }
}
