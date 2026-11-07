package dev.lyy.friendlysync.serialization;

import com.github.luben.zstd.Zstd;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Service for compressing and decompressing serialized snapshot payloads.
 */
public final class CompressionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompressionService.class);
    private static boolean zstdAvailable = true;

    static {
        try {
            byte[] test = "test".getBytes();
            byte[] compressed = Zstd.compress(test);
            Zstd.decompress(compressed, test.length);
        } catch (Throwable t) {
            zstdAvailable = false;
            LOGGER.warn("ZSTD native library could not be loaded; falling back to GZIP: {}", t.getMessage());
        }
    }

    private final CompressionAlgorithm preferredAlgorithm;
    private final int minimumSizeBytes;

    public CompressionService(CompressionAlgorithm preferredAlgorithm, int minimumSizeBytes) {
        this.preferredAlgorithm = Preconditions.checkNotNull(preferredAlgorithm, "preferredAlgorithm cannot be null");
        this.minimumSizeBytes = Math.max(0, minimumSizeBytes);
    }

    /**
     * Compresses the input data according to configured thresholds and algorithms.
     * Output format: [1 byte algorithm code] [4 bytes original length] [compressed or raw bytes]
     */
    public byte[] compress(byte[] data) {
        Preconditions.checkNotNull(data, "data cannot be null");

        if (data.length < minimumSizeBytes || preferredAlgorithm == CompressionAlgorithm.NONE) {
            return wrapWithHeader(CompressionAlgorithm.NONE, data.length, data);
        }

        CompressionAlgorithm algorithm = preferredAlgorithm;
        if (algorithm == CompressionAlgorithm.ZSTD && !zstdAvailable) {
            algorithm = CompressionAlgorithm.GZIP;
        }

        try {
            byte[] compressed;
            if (algorithm == CompressionAlgorithm.ZSTD) {
                compressed = Zstd.compress(data);
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                    gzos.write(data);
                }
                compressed = baos.toByteArray();
            }

            // Only use compression if it actually saved space
            if (compressed.length < data.length) {
                return wrapWithHeader(algorithm, data.length, compressed);
            } else {
                return wrapWithHeader(CompressionAlgorithm.NONE, data.length, data);
            }
        } catch (Exception e) {
            LOGGER.warn("Compression failed, falling back to uncompressed payload: {}", e.getMessage());
            return wrapWithHeader(CompressionAlgorithm.NONE, data.length, data);
        }
    }

    /**
     * Decompresses payload with algorithm header.
     */
    public byte[] decompress(byte[] payload) {
        Preconditions.checkNotNull(payload, "payload cannot be null");
        if (payload.length < 5) {
            throw new SerializationException("Payload too short to contain compression header: " + payload.length + " bytes");
        }

        byte code = payload[0];
        CompressionAlgorithm algorithm = CompressionAlgorithm.fromCode(code);

        int originalLength = ((payload[1] & 0xFF) << 24)
            | ((payload[2] & 0xFF) << 16)
            | ((payload[3] & 0xFF) << 8)
            | (payload[4] & 0xFF);

        if (originalLength < 0 || originalLength > 64 * 1024 * 1024) {
            throw new SerializationException("Invalid uncompressed payload length: " + originalLength);
        }

        int compressedLen = payload.length - 5;
        if (algorithm == CompressionAlgorithm.NONE) {
            if (compressedLen != originalLength) {
                throw new SerializationException("Uncompressed payload length mismatch: expected "
                    + originalLength + ", found " + compressedLen);
            }
            byte[] raw = new byte[compressedLen];
            System.arraycopy(payload, 5, raw, 0, compressedLen);
            return raw;
        }

        try {
            if (algorithm == CompressionAlgorithm.ZSTD) {
                if (!zstdAvailable) {
                    throw new SerializationException("Payload is ZSTD compressed but ZSTD native library is unavailable");
                }
                byte[] compressedBytes = new byte[compressedLen];
                System.arraycopy(payload, 5, compressedBytes, 0, compressedLen);
                byte[] result = Zstd.decompress(compressedBytes, originalLength);
                if (result.length != originalLength) {
                    throw new SerializationException("ZSTD payload length mismatch");
                }
                return result;
            } else if (algorithm == CompressionAlgorithm.GZIP) {
                ByteArrayInputStream bais = new ByteArrayInputStream(payload, 5, compressedLen);
                try (GZIPInputStream gzis = new GZIPInputStream(bais)) {
                    byte[] result = gzis.readNBytes(originalLength + 1);
                    if (result.length != originalLength || gzis.read() != -1) {
                        throw new SerializationException("GZIP payload length mismatch");
                    }
                    return result;
                }
            } else {
                throw new SerializationException("Unsupported compression algorithm: " + algorithm);
            }
        } catch (IOException | RuntimeException e) {
            throw new SerializationException("Failed to decompress payload with algorithm " + algorithm, e);
        }
    }

    private byte[] wrapWithHeader(CompressionAlgorithm algorithm, int originalLength, byte[] payload) {
        byte[] result = new byte[5 + payload.length];
        result[0] = algorithm.getCode();
        result[1] = (byte) ((originalLength >>> 24) & 0xFF);
        result[2] = (byte) ((originalLength >>> 16) & 0xFF);
        result[3] = (byte) ((originalLength >>> 8) & 0xFF);
        result[4] = (byte) (originalLength & 0xFF);
        System.arraycopy(payload, 0, result, 5, payload.length);
        return result;
    }
}
