package br.com.bernisoftware.bfocus.monitor;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Assinatura v2 da identidade, a MESMA do widget (monitor/BRIEF.md, seção 6). */
final class Signing {
    /** Recalcula a assinatura feita há mais de 6 dias. */
    static final long MAX_AGE_SECONDS = 6L * 24 * 3600;

    private Signing() {
    }

    static String userHash(String secret, long ts, String userExternalId, String customerExternalId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(("v2:" + ts + ":" + userExternalId + ":" + customerExternalId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return "v2." + ts + "." + sb;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }

    static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
