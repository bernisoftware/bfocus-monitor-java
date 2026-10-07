package br.com.bernisoftware.bfocus.monitor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Opções de {@link BfocusMonitor#init(MonitorOptions)}:
 *
 * <pre>{@code
 * BfocusMonitor.init(MonitorOptions.builder()
 *     .key("bf_mon_...")
 *     .release("1.4.2").environment("production").build());
 * }</pre>
 */
public final class MonitorOptions {
    static final String DEFAULT_BASE_URL = "https://api.bfocus.com.br";

    private final String key;
    private final String release;
    private final String environment;
    private final String baseUrl;
    private final double sampleRate;
    private final List<String> ignore;
    private final List<Pattern> ignorePatterns;
    private final UnaryOperator<MonitorEvent> beforeSend;
    private final String signingSecret;
    private final boolean autoCapture;
    private final List<String> inAppPrefixes;
    // Ajustes internos (testes).
    final Duration retryDelay;
    final Duration batchInterval;
    final LongSupplier signingClock;
    final Duration heartbeatInterval;

    private MonitorOptions(Builder b) {
        this.key = b.key == null ? "" : b.key.trim();
        this.release = b.release;
        this.environment = b.environment == null || b.environment.isEmpty() ? "production" : b.environment;
        String base = b.baseUrl == null || b.baseUrl.trim().isEmpty() ? DEFAULT_BASE_URL : b.baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;
        double rate = Double.isNaN(b.sampleRate) ? 1.0 : b.sampleRate;
        this.sampleRate = Math.max(0.0, Math.min(1.0, rate));
        this.ignore = Collections.unmodifiableList(new ArrayList<>(b.ignore));
        this.ignorePatterns = Collections.unmodifiableList(new ArrayList<>(b.ignorePatterns));
        this.beforeSend = b.beforeSend;
        this.signingSecret = b.signingSecret == null || b.signingSecret.isEmpty() ? null : b.signingSecret;
        this.autoCapture = b.autoCapture;
        this.inAppPrefixes = Collections.unmodifiableList(new ArrayList<>(b.inAppPrefixes));
        this.retryDelay = b.retryDelay;
        this.batchInterval = b.batchInterval;
        this.signingClock = b.signingClock;
        this.heartbeatInterval = b.heartbeatInterval;
    }

    /** @return um builder novo */
    public static Builder builder() {
        return new Builder();
    }

    /** @return a chave do agente ({@code bf_mon_...}) */
    public String key() {
        return key;
    }

    /** @return a versão do sistema, ou {@code null} */
    public String release() {
        return release;
    }

    /** @return o ambiente */
    public String environment() {
        return environment;
    }

    /** @return o endereço da API, sem barra final */
    public String baseUrl() {
        return baseUrl;
    }

    /** @return a fração enviada (0 a 1) */
    public double sampleRate() {
        return sampleRate;
    }

    /** @return textos que, contidos na mensagem, fazem o erro ser ignorado */
    public List<String> ignore() {
        return ignore;
    }

    /** @return expressões que, achadas na mensagem, fazem o erro ser ignorado */
    public List<Pattern> ignorePatterns() {
        return ignorePatterns;
    }

    /** @return a última chance de alterar ou descartar o evento, ou {@code null} */
    public UnaryOperator<MonitorEvent> beforeSend() {
        return beforeSend;
    }

    /** @return o segredo de assinatura (só servidor), ou {@code null} */
    public String signingSecret() {
        return signingSecret;
    }

    /** @return se instala os ganchos globais */
    public boolean autoCapture() {
        return autoCapture;
    }

    /** @return prefixos de pacote que são do sistema */
    public List<String> inAppPrefixes() {
        return inAppPrefixes;
    }

    /** Construtor das opções. */
    public static final class Builder {
        private String key;
        private String release;
        private String environment = "production";
        private String baseUrl = DEFAULT_BASE_URL;
        private double sampleRate = 1.0;
        private final List<String> ignore = new ArrayList<>();
        private final List<Pattern> ignorePatterns = new ArrayList<>();
        private UnaryOperator<MonitorEvent> beforeSend;
        private String signingSecret;
        private boolean autoCapture = true;
        private final List<String> inAppPrefixes = new ArrayList<>();
        private Duration retryDelay = Duration.ofSeconds(2);
        private Duration batchInterval = Duration.ofSeconds(1);
        private LongSupplier signingClock;
        private Duration heartbeatInterval = Duration.ofMinutes(5);

        private Builder() {
        }

        /**
         * Chave de envio do agente (Monitoramento, Agentes). Obrigatória.
         *
         * @param key {@code bf_mon_...}
         * @return este builder
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Versão do SEU sistema (ex.: {@code 1.4.2}), ligada às notas de versão do produto.
         *
         * @param release a versão
         * @return este builder
         */
        public Builder release(String release) {
            this.release = release;
            return this;
        }

        /**
         * Ambiente. Padrão {@code production}.
         *
         * @param environment {@code production}, {@code staging}...
         * @return este builder
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Endereço da API. Padrão {@code https://api.bfocus.com.br}.
         *
         * @param baseUrl sem barra final (se vier, é tirada)
         * @return este builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Fração dos erros enviada. Padrão 1 (todos).
         *
         * @param sampleRate de 0 a 1
         * @return este builder
         */
        public Builder sampleRate(double sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Mensagens a ignorar: o erro cuja mensagem CONTÉM um destes textos não é enviado.
         *
         * @param texts os textos
         * @return este builder
         */
        public Builder ignore(String... texts) {
            for (String t : texts) if (t != null && !t.isEmpty()) ignore.add(t);
            return this;
        }

        /**
         * Mensagens a ignorar por expressão regular ({@code find}).
         *
         * @param patterns as expressões
         * @return este builder
         */
        public Builder ignore(Pattern... patterns) {
            for (Pattern p : patterns) if (p != null) ignorePatterns.add(p);
            return this;
        }

        /**
         * Mensagens a ignorar (texto contido).
         *
         * @param texts os textos
         * @return este builder
         */
        public Builder ignore(Collection<String> texts) {
            return ignore(texts.toArray(new String[0]));
        }

        /**
         * Última chance de alterar o evento ou descartá-lo (devolva {@code null}).
         *
         * @param beforeSend a função
         * @return este builder
         */
        public Builder beforeSend(UnaryOperator<MonitorEvent> beforeSend) {
            this.beforeSend = beforeSend;
            return this;
        }

        /**
         * SÓ NO SERVIDOR: segredo da chave de assinatura do sistema (o mesmo do {@code userHash} do widget).
         * Com ele, {@link BfocusMonitor#setUser(String, String)} assina a identidade sozinho.
         *
         * @param signingSecret o segredo
         * @return este builder
         */
        public Builder signingSecret(String signingSecret) {
            this.signingSecret = signingSecret;
            return this;
        }

        /**
         * Instalar o gancho de exceção não tratada das threads. Padrão {@code true}.
         *
         * @param autoCapture liga ou desliga
         * @return este builder
         */
        public Builder autoCapture(boolean autoCapture) {
            this.autoCapture = autoCapture;
            return this;
        }

        /**
         * Prefixos de pacote que são do SEU sistema (ex.: {@code com.acme.}) quando a heurística não basta.
         *
         * @param prefixes os prefixos
         * @return este builder
         */
        public Builder inAppPrefixes(String... prefixes) {
            inAppPrefixes.addAll(Arrays.asList(prefixes));
            inAppPrefixes.removeIf(p -> p == null || p.isEmpty());
            return this;
        }

        Builder retryDelay(Duration retryDelay) {
            this.retryDelay = Objects.requireNonNull(retryDelay);
            return this;
        }

        Builder batchInterval(Duration batchInterval) {
            this.batchInterval = Objects.requireNonNull(batchInterval);
            return this;
        }

        Builder signingClock(LongSupplier signingClock) {
            this.signingClock = signingClock;
            return this;
        }

        Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval);
            return this;
        }

        /** @return as opções */
        public MonitorOptions build() {
            return new MonitorOptions(this);
        }
    }
}
