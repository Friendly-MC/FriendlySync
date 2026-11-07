package dev.lyy.friendlysync.util;

/**
 * Utility for masking passwords and sensitive tokens in logs and reports.
 */
public final class SecretMasker {

    private SecretMasker() {
    }

    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "******";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 1) + "****" + secret.substring(secret.length() - 1);
    }
}
