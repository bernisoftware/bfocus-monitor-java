package br.com.bernisoftware.bfocus.monitor;

/** Nível do evento. */
public enum Level {
    /** O processo (ou a thread) vai morrer: exceção não tratada no topo. */
    FATAL("fatal"),
    /** Erro (padrão das exceções). */
    ERROR("error"),
    /** Aviso. */
    WARNING("warning"),
    /** Informação (padrão de {@code captureMessage}). */
    INFO("info");

    private final String wire;

    Level(String wire) {
        this.wire = wire;
    }

    /** @return o valor no corpo ({@code fatal}, {@code error}, {@code warning}, {@code info}) */
    public String wire() {
        return wire;
    }
}
