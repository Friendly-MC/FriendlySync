package dev.lyy.friendlysync;

import dev.lyy.friendlysync.serialization.CompressionAlgorithm;
import dev.lyy.friendlysync.serialization.CompressionService;
import dev.lyy.friendlysync.serialization.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressionServiceTest {

    @Test
    @DisplayName("Compression: GZIP compress and decompress preserves exact bytes")
    void testGzipRoundTrip() {
        CompressionService service = new CompressionService(CompressionAlgorithm.GZIP, 10);
        byte[] input = "Hello, FriendlySync Minecraft Multi-Platform World!".getBytes(StandardCharsets.UTF_8);

        byte[] compressed = service.compress(input);
        byte[] decompressed = service.decompress(compressed);

        assertThat(decompressed).isEqualTo(input);
    }

    @Test
    @DisplayName("Compression: NONE algorithm preserves exact bytes")
    void testNoneRoundTrip() {
        CompressionService service = new CompressionService(CompressionAlgorithm.NONE, 0);
        byte[] input = "Uncompressed payload test".getBytes(StandardCharsets.UTF_8);

        byte[] wrapped = service.compress(input);
        byte[] unwrapped = service.decompress(wrapped);

        assertThat(unwrapped).isEqualTo(input);
    }

    @Test
    @DisplayName("Compression: Truncated or corrupt payload throws SerializationException")
    void testCorruptPayload() {
        CompressionService service = new CompressionService(CompressionAlgorithm.GZIP, 10);

        // Header too short
        assertThatThrownBy(() -> service.decompress(new byte[]{1, 2, 3}))
            .isInstanceOf(SerializationException.class);

        // Corrupt GZIP payload
        byte[] corruptGzip = new byte[]{CompressionAlgorithm.GZIP.getCode(), 0, 0, 0, 20, 99, 98, 97};
        assertThatThrownBy(() -> service.decompress(corruptGzip))
            .isInstanceOf(SerializationException.class);
    }
}
