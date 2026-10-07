package br.com.bernisoftware.bfocus.monitor;

/** Quem foi afetado (assinado aqui ou dado pelo servidor do cliente). */
final class Identity {
    String userExternalId;
    String customerExternalId;
    /** Dado pelo cliente: vai como veio. */
    String givenHash;
    /** Calculado aqui com o signingSecret (recalculado depois de 6 dias). */
    String signedHash;
    long signedAt;
}
