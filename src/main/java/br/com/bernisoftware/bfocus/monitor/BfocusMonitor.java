package br.com.bernisoftware.bfocus.monitor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitoramento de erros do bFocus. Uma linha liga tudo:
 *
 * <pre>{@code
 * BfocusMonitor.init(MonitorOptions.builder().key("bf_mon_...").release("1.4.2").environment("production").build());
 * }</pre>
 *
 * <p>Nenhuma chamada de rede na thread do {@code init}: o sinal de vida (no init e a cada 5 min) e o envio
 * em lote saem em threads daemon; nada aqui derruba o app.
 */
public final class BfocusMonitor {
    /** Versão deste pacote (a mesma do pom.xml; um teste trava). */
    public static final String VERSION = "0.1.0";

    /** Nome no campo {@code sdk.name} e no header {@code X-bFocus-Client}. */
    public static final String SDK_NAME = "bfocus-monitor-java";

    private static final Duration EXIT_FLUSH = Duration.ofSeconds(2);
    private static final Object GATE = new Object();
    private static final AtomicBoolean HANDLER_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean();
    private static volatile MonitorClient client;

    private BfocusMonitor() {
    }

    static MonitorClient current() {
        return client;
    }

    /**
     * Liga o monitor (ou religa com novas opções, e volta a enviar se a chave tinha sido recusada). Com
     * {@code autoCapture} instala {@link Thread#setDefaultUncaughtExceptionHandler} (nível fatal, flush de até
     * 2 s, e depois o handler que já existia continua rodando) e um shutdown hook que envia o que falta.
     *
     * @param options as opções
     * @throws IllegalArgumentException chave vazia
     * @throws NullPointerException sem opções
     */
    public static void init(MonitorOptions options) {
        if (options == null) throw new NullPointerException("options");
        if (options.key().isEmpty()) throw new IllegalArgumentException("A chave do agente (key) é obrigatória.");
        MonitorClient next = new MonitorClient(options);
        MonitorClient previous;
        synchronized (GATE) {
            previous = client;
            client = next;
        }
        if (previous != null) previous.close(EXIT_FLUSH);
        next.startHeartbeat(); // sinal de vida: em segundo plano, nunca nesta thread
        try {
            if (HOOK_INSTALLED.compareAndSet(false, true)) {
                Thread hook = new Thread(BfocusMonitor::onShutdown, "bfocus-monitor-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
            }
            if (options.autoCapture() && HANDLER_INSTALLED.compareAndSet(false, true)) {
                Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler(new UncaughtHandler(original));
            }
        } catch (RuntimeException ignored) {
            // ambiente restrito: segue só com a captura manual
        }
    }

    /**
     * Manda o erro (a causa raiz, quando encadeado) com nível {@code error}.
     *
     * @param error o erro
     */
    public static void captureException(Throwable error) {
        captureException(error, Level.ERROR, null, null);
    }

    /**
     * Manda o erro com nível, marcadores e agrupamento manual.
     *
     * @param error o erro
     * @param level o nível ({@code null} vira {@code ERROR})
     * @param tags marcadores só deste evento, ou {@code null}
     * @param fingerprint agrupamento manual, ou {@code null}
     */
    public static void captureException(Throwable error, Level level, Map<String, String> tags, List<String> fingerprint) {
        try {
            MonitorClient c = client;
            if (c != null) c.captureException(error, level, tags, fingerprint);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Manda uma mensagem como evento (nível {@code info}).
     *
     * @param message a mensagem
     */
    public static void captureMessage(String message) {
        captureMessage(message, Level.INFO);
    }

    /**
     * Manda uma mensagem como evento.
     *
     * @param message a mensagem
     * @param level o nível
     */
    public static void captureMessage(String message, Level level) {
        try {
            MonitorClient c = client;
            if (c != null) c.captureMessage(message, level);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Quem foi afetado. Com {@code signingSecret}, o pacote assina sozinho (v2, a mesma do widget). Dentro de
     * uma requisição ({@link BfocusMonitorFilter} ou {@link #beginScope}) vale só para ela. {@code null} nos
     * dois limpa.
     *
     * @param userExternalId id da pessoa no SEU sistema
     * @param customerExternalId id do cliente no SEU sistema
     */
    public static void setUser(String userExternalId, String customerExternalId) {
        setUser(userExternalId, customerExternalId, null);
    }

    /**
     * Quem foi afetado, com a assinatura já pronta (dada pelo seu servidor; a mesma do widget).
     *
     * @param userExternalId id da pessoa no SEU sistema
     * @param customerExternalId id do cliente no SEU sistema
     * @param userHash assinatura v2, ou {@code null} para o pacote assinar com o {@code signingSecret}
     */
    public static void setUser(String userExternalId, String customerExternalId, String userHash) {
        try {
            MonitorClient c = client;
            if (c != null) c.setUser(userExternalId, customerExternalId, userHash);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Marcador que vai em todos os eventos (ou só nos da requisição atual).
     *
     * @param key a chave
     * @param value o valor
     */
    public static void setTag(String key, String value) {
        try {
            MonitorClient c = client;
            if (c != null) c.setTag(key, value);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Passo antes do erro (nível {@code info}); os 30 últimos vão junto.
     *
     * @param category categoria (http, sql, navigation...)
     * @param message o que aconteceu
     */
    public static void addBreadcrumb(String category, String message) {
        addBreadcrumb(category, message, Level.INFO);
    }

    /**
     * Passo antes do erro; os 30 últimos vão junto.
     *
     * @param category categoria (http, sql, navigation...)
     * @param message o que aconteceu
     * @param level o nível
     */
    public static void addBreadcrumb(String category, String message, Level level) {
        try {
            MonitorClient c = client;
            if (c != null) c.addBreadcrumb(category, message, level);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Envia o que está na fila e espera, até o teto (CLI, serverless).
     *
     * @param timeout o teto
     * @return se a fila esvaziou
     */
    public static boolean flush(Duration timeout) {
        try {
            MonitorClient c = client;
            return c == null || c.flush(timeout);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Envia o que falta (até 2 s) e desliga. Depois disso, só um novo {@code init} volta a capturar. */
    public static void close() {
        MonitorClient c;
        synchronized (GATE) {
            c = client;
            client = null;
        }
        try {
            if (c != null) c.close(EXIT_FLUSH);
        } catch (Throwable ignored) {
            // nunca derruba o app
        }
    }

    /**
     * Abre o contexto de UMA requisição/tarefa nesta thread (identidade, marcadores e passos isolados). O
     * {@link BfocusMonitorFilter} já faz isso; use em filas, jobs e frameworks sem integração pronta, sempre
     * com try-with-resources.
     *
     * @param transaction ex.: {@code POST /pedidos}, ou {@code null}
     * @param url URL da requisição (a query string é cortada), ou {@code null}
     * @return o contexto; feche-o no fim
     */
    public static MonitorScope beginScope(String transaction, String url) {
        return MonitorScope.begin(transaction, url);
    }

    static void onShutdown() {
        try {
            MonitorClient c = client;
            if (c != null) c.flush(EXIT_FLUSH);
        } catch (Throwable ignored) {
            // saindo de qualquer jeito
        }
    }

    /** Captura (fatal), dá até 2 s para enviar e passa adiante: o app quebra como quebraria sem o monitor. */
    static final class UncaughtHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler original;

        UncaughtHandler(Thread.UncaughtExceptionHandler original) {
            this.original = original;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                MonitorClient c = client;
                if (c != null && c.options().autoCapture()) {
                    c.captureException(error, Level.FATAL, null, null);
                    c.flush(EXIT_FLUSH);
                }
            } catch (Throwable ignored) {
                // segue para o comportamento normal
            }
            if (original != null) {
                original.uncaughtException(thread, error);
            } else if (!(error instanceof ThreadDeath)) {
                // O que o ThreadGroup padrão faz (chamá-lo daqui voltaria para este handler).
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace(System.err);
            }
        }
    }
}
