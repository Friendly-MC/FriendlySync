package dev.lyy.friendlysync.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fast and cryptographically sound checksum calculation utilities.
 */
public final class DigestUtils {

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private DigestUtils() {
    }

    /**
     * Computes the SHA-256 hex string representation of the given byte array.
     *
     * @param data byte array to hash
     * @return 64-character lowercase hexadecimal hash string
     */
    public static String sha256(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HEX_FORMAT.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }

    public static String sha256(String str) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        return sha256(str.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
