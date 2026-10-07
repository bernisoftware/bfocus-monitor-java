package br.com.bernisoftware.bfocus.monitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contexto de UMA requisição/tarefa (ThreadLocal): a identidade, os marcadores e os passos dela não vazam
 * para outra requisição simultânea. Feche no {@code finally} (ou com try-with-resources): a thread volta
 * para o pool limpa.
 *
 * <pre>{@code
 * try (MonitorScope scope = BfocusMonitor.beginScope("job FecharCaixa", null)) {
 *     BfocusMonitor.setUser(job.usuarioId(), job.clienteId());
 *     ...
 * }
 * }</pre>
 */
public final class MonitorScope implements AutoCloseable {
    private static final ThreadLocal<MonitorScope> CURRENT = new ThreadLocal<>();

    private final MonitorScope previous;
    private final Thread owner;
    private boolean closed;
    final Object gate = new Object();
    Identity identity;
    final Map<String, String> tags = new LinkedHashMap<>();
    final List<MonitorEvent.Breadcrumb> breadcrumbs = new ArrayList<>();
    final String transaction;
    final String url;

    private MonitorScope(MonitorScope previous, String transaction, String url) {
        this.previous = previous;
        this.owner = Thread.currentThread();
        this.transaction = transaction;
        this.url = stripQuery(url);
    }

    static MonitorScope current() {
        return CURRENT.get();
    }

    static MonitorScope begin(String transaction, String url) {
        MonitorScope scope = new MonitorScope(CURRENT.get(), transaction, url);
        CURRENT.set(scope);
        return scope;
    }

    /** Devolve a thread ao contexto anterior (ou a nenhum). */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (Thread.currentThread() == owner && CURRENT.get() == this) {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** URL sem query string nem fragmento (é onde mora token, e-mail, CPF). */
    static String stripQuery(String url) {
        if (url == null || url.isEmpty()) return null;
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int cut = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
        return cut >= 0 ? url.substring(0, cut) : url;
    }
}
