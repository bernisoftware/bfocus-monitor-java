package br.com.bernisoftware.bfocus.monitor;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Filtro Servlet (jakarta: Spring Boot 3, Tomcat 10+, Jetty 11+): abre o contexto da requisição
 * (transaction {@code "METHOD /path"}, URL sem query, identidade e marcadores só desta requisição), captura a
 * exceção que escapar e RELANÇA. O contexto (ThreadLocal) é limpo no fim, antes de a thread voltar ao pool.
 *
 * <p>Spring Boot:
 *
 * <pre>{@code
 * @Bean
 * FilterRegistrationBean<BfocusMonitorFilter> bfocusMonitorFilter() {
 *     FilterRegistrationBean<BfocusMonitorFilter> reg = new FilterRegistrationBean<>(new BfocusMonitorFilter());
 *     reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
 *     return reg;
 * }
 * }</pre>
 */
public class BfocusMonitorFilter implements Filter {
    /** Atributo da requisição com o {@link MonitorScope} dela. */
    public static final String SCOPE_ATTRIBUTE = "br.com.bernisoftware.bfocus.monitor.scope";

    /** Filtro sem configuração (o monitor vem do {@link BfocusMonitor#init}). */
    public BfocusMonitorFilter() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        MonitorScope scope = null;
        try {
            String transaction = null;
            String url = null;
            if (request instanceof HttpServletRequest) {
                HttpServletRequest http = (HttpServletRequest) request;
                String path = http.getRequestURI();
                if (path == null || path.isEmpty()) path = "/";
                transaction = http.getMethod() + " " + path;
                StringBuffer full = http.getRequestURL();
                url = full == null ? null : full.toString();
            }
            scope = MonitorScope.begin(transaction, url);
            request.setAttribute(SCOPE_ATTRIBUTE, scope);
        } catch (RuntimeException ignored) {
            // sem contexto: a requisição segue igual
        }
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            BfocusMonitor.captureException(e);
            throw e;
        } finally {
            if (scope != null) scope.close();
        }
    }
}
