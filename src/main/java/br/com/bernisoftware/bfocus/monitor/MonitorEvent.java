package br.com.bernisoftware.bfocus.monitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O evento como vai para o bFocus ({@code POST /api/v1/monitor/events}). É o que o {@code beforeSend} recebe:
 * altere à vontade ou devolva {@code null} para descartar. Campo {@code null} (ou coleção vazia) não vai no corpo.
 */
public final class MonitorEvent {
    private String timestamp = "";
    private Level level = Level.ERROR;
    private String release;
    private String environment;
    private ExceptionInfo exception = new ExceptionInfo();
    private String transaction;
    private String url;
    private User user;
    private Customer customer;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<Breadcrumb> breadcrumbs = new ArrayList<>();
    private List<String> fingerprint;
    private Map<String, Map<String, String>> contexts = new LinkedHashMap<>();
    private Sdk sdk = new Sdk();

    /** @return ISO 8601 UTC com {@code Z} */
    public String getTimestamp() { return timestamp; }
    /** @param timestamp ISO 8601 UTC */
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    /** @return o nível */
    public Level getLevel() { return level; }
    /** @param level o nível */
    public void setLevel(Level level) { this.level = level; }
    /** @return a versão do sistema */
    public String getRelease() { return release; }
    /** @param release a versão do sistema */
    public void setRelease(String release) { this.release = release; }
    /** @return o ambiente */
    public String getEnvironment() { return environment; }
    /** @param environment o ambiente */
    public void setEnvironment(String environment) { this.environment = environment; }
    /** @return o erro (a causa raiz, quando encadeado) */
    public ExceptionInfo getException() { return exception; }
    /** @param exception o erro */
    public void setException(ExceptionInfo exception) { this.exception = exception; }
    /** @return ex.: {@code POST /pedidos} */
    public String getTransaction() { return transaction; }
    /** @param transaction ex.: {@code POST /pedidos} */
    public void setTransaction(String transaction) { this.transaction = transaction; }
    /** @return a URL da requisição, sem query string */
    public String getUrl() { return url; }
    /** @param url a URL, sem query string */
    public void setUrl(String url) { this.url = url; }
    /** @return a pessoa afetada */
    public User getUser() { return user; }
    /** @param user a pessoa afetada */
    public void setUser(User user) { this.user = user; }
    /** @return o cliente afetado */
    public Customer getCustomer() { return customer; }
    /** @param customer o cliente afetado */
    public void setCustomer(Customer customer) { this.customer = customer; }
    /** @return os marcadores (mutável) */
    public Map<String, String> getTags() { return tags; }
    /** @param tags os marcadores */
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    /** @return os passos antes do erro (mutável) */
    public List<Breadcrumb> getBreadcrumbs() { return breadcrumbs; }
    /** @param breadcrumbs os passos */
    public void setBreadcrumbs(List<Breadcrumb> breadcrumbs) { this.breadcrumbs = breadcrumbs; }
    /** @return o agrupamento manual, ou {@code null} */
    public List<String> getFingerprint() { return fingerprint; }
    /** @param fingerprint o agrupamento manual */
    public void setFingerprint(List<String> fingerprint) { this.fingerprint = fingerprint; }
    /** @return os contextos (runtime, sistema operacional) */
    public Map<String, Map<String, String>> getContexts() { return contexts; }
    /** @param contexts os contextos */
    public void setContexts(Map<String, Map<String, String>> contexts) { this.contexts = contexts; }
    /** @return este pacote */
    public Sdk getSdk() { return sdk; }
    /** @param sdk este pacote */
    public void setSdk(Sdk sdk) { this.sdk = sdk; }

    /** O erro. */
    public static final class ExceptionInfo {
        private String type = "";
        private String message = "";
        private List<Frame> frames = new ArrayList<>();

        /** @return o nome completo da classe (ex.: {@code java.lang.NullPointerException}) */
        public String getType() { return type; }
        /** @param type o nome da classe */
        public void setType(String type) { this.type = type; }
        /** @return a mensagem */
        public String getMessage() { return message; }
        /** @param message a mensagem */
        public void setMessage(String message) { this.message = message; }
        /** @return os frames de FORA para DENTRO (o último é onde estourou) */
        public List<Frame> getFrames() { return frames; }
        /** @param frames os frames, de fora para dentro */
        public void setFrames(List<Frame> frames) { this.frames = frames; }
    }

    /** Um frame da pilha. */
    public static final class Frame {
        private String file;
        private String function;
        private Integer line;
        private Integer col;
        private boolean inApp;

        /** @return o arquivo (ex.: {@code com/acme/Pedido.java}) */
        public String getFile() { return file; }
        /** @param file o arquivo */
        public void setFile(String file) { this.file = file; }
        /** @return {@code Classe.metodo} */
        public String getFunction() { return function; }
        /** @param function {@code Classe.metodo} */
        public void setFunction(String function) { this.function = function; }
        /** @return a linha, ou {@code null} */
        public Integer getLine() { return line; }
        /** @param line a linha */
        public void setLine(Integer line) { this.line = line; }
        /** @return a coluna, ou {@code null} */
        public Integer getCol() { return col; }
        /** @param col a coluna */
        public void setCol(Integer col) { this.col = col; }
        /** @return se é código do sistema ({@code false} para biblioteca) */
        public boolean isInApp() { return inApp; }
        /** @param inApp se é código do sistema */
        public void setInApp(boolean inApp) { this.inApp = inApp; }
    }

    /** Pessoa afetada. */
    public static final class User {
        private String externalId;
        private String userHash;

        /**
         * @param externalId id da pessoa no SEU sistema
         * @param userHash assinatura v2, ou {@code null}
         */
        public User(String externalId, String userHash) {
            this.externalId = externalId;
            this.userHash = userHash;
        }

        /** @return o id da pessoa no SEU sistema */
        public String getExternalId() { return externalId; }
        /** @param externalId o id da pessoa */
        public void setExternalId(String externalId) { this.externalId = externalId; }
        /** @return a assinatura v2 (a mesma do widget) */
        public String getUserHash() { return userHash; }
        /** @param userHash a assinatura v2 */
        public void setUserHash(String userHash) { this.userHash = userHash; }
    }

    /** Cliente afetado. */
    public static final class Customer {
        private String externalId;

        /** @param externalId id do cliente no SEU sistema */
        public Customer(String externalId) {
            this.externalId = externalId;
        }

        /** @return o id do cliente no SEU sistema */
        public String getExternalId() { return externalId; }
        /** @param externalId o id do cliente */
        public void setExternalId(String externalId) { this.externalId = externalId; }
    }

    /** Passo antes do erro. */
    public static final class Breadcrumb {
        private String timestamp;
        private String category;
        private String message;
        private Level level;

        /**
         * @param timestamp ISO 8601 UTC
         * @param category categoria (http, navigation, sql...)
         * @param message o que aconteceu
         * @param level o nível
         */
        public Breadcrumb(String timestamp, String category, String message, Level level) {
            this.timestamp = timestamp;
            this.category = category;
            this.message = message;
            this.level = level;
        }

        /** @return ISO 8601 UTC */
        public String getTimestamp() { return timestamp; }
        /** @return a categoria */
        public String getCategory() { return category; }
        /** @return o que aconteceu */
        public String getMessage() { return message; }
        /** @return o nível */
        public Level getLevel() { return level; }
    }

    /** Identificação do pacote. */
    public static final class Sdk {
        private String name = BfocusMonitor.SDK_NAME;
        private String version = BfocusMonitor.VERSION;

        /** @return {@code bfocus-monitor-java} */
        public String getName() { return name; }
        /** @param name o nome */
        public void setName(String name) { this.name = name; }
        /** @return a versão do pacote */
        public String getVersion() { return version; }
        /** @param version a versão */
        public void setVersion(String version) { this.version = version; }
    }
}
