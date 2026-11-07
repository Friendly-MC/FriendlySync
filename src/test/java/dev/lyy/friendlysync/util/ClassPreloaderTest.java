package dev.lyy.friendlysync.util;

import dev.lyy.friendlysync.model.EffectSnapshot;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.ExperienceSnapshot;
import dev.lyy.friendlysync.model.FlightSnapshot;
import dev.lyy.friendlysync.model.GameModeSnapshot;
import dev.lyy.friendlysync.model.HungerSnapshot;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.LocationSnapshot;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.VitalSnapshot;
import dev.lyy.friendlysync.serialization.CompressionAlgorithm;
import dev.lyy.friendlysync.serialization.CompressionService;
import dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ClassPreloaderTest {

    @Test
    @DisplayName("ClassPreloader: Preloads and warms classes into Metaspace without error")
    void testClassPreloading() {
        int preloaded = ClassPreloader.preload(getClass().getClassLoader(), ClassPreloader.class);
        assertThat(preloaded).isGreaterThan(0);

        // Verify critical model records are loaded and operational
        GameModeSnapshot gameMode = new GameModeSnapshot("SURVIVAL");
        assertThat(gameMode.gameMode()).isEqualTo("SURVIVAL");
    }

    @Test
    @DisplayName("ClassPreloader: Deserialization of GameModeSnapshot succeeds across worker threads")
    void testDeserializationWithGameModeOnWorkerThread() throws Exception {
        CompressionService compressionService = new CompressionService(CompressionAlgorithm.NONE, 0);
        DefaultPlayerStateSerializer serializer = new DefaultPlayerStateSerializer(compressionService);

        UUID playerUuid = UUID.randomUUID();
        PlayerStateSnapshot original = new PlayerStateSnapshot(
            playerUuid,
            1L,
            100L,
            new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null),
            new EnderChestSnapshot(new byte[27][]),
            new ExperienceSnapshot(10, 0.5f, 100),
            new VitalSnapshot(20.0, 20.0, 0.0),
            new HungerSnapshot(20, 5.0f, 0.0f),
            List.of(),
            new GameModeSnapshot("CREATIVE"),
            new FlightSnapshot(false, false, 0, 300, 0.0f),
            new LocationSnapshot("world", 0.0, 64.0, 0.0, 0.0f, 0.0f),
            Map.of(),
            Instant.now(),
            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
        );

        byte[] serialized = serializer.serialize(original);

        var executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Test-Worker");
            t.setContextClassLoader(getClass().getClassLoader());
            return t;
        });

        try {
            CompletableFuture<PlayerStateSnapshot> future = CompletableFuture.supplyAsync(() -> {
                return serializer.deserialize(serialized);
            }, executor);

            PlayerStateSnapshot deserialized = future.get();
            assertThat(deserialized).isNotNull();
            assertThat(deserialized.gameMode()).isNotNull();
            assertThat(deserialized.gameMode().gameMode()).isEqualTo("CREATIVE");
        } finally {
            executor.shutdown();
        }
    }
}
