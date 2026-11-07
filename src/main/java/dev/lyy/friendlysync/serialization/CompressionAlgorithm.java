package dev.lyy.friendlysync.serialization;

/**
 * Supported payload compression algorithms.
 */
public enum CompressionAlgorithm {
    NONE((byte) 0),
    GZIP((byte) 1),
    ZSTD((byte) 2);

    private final byte code;

    CompressionAlgorithm(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static CompressionAlgorithm fromCode(byte code) {
        for (CompressionAlgorithm algorithm : values()) {
            if (algorithm.code == code) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unknown compression algorithm code: " + code);
    }
}
