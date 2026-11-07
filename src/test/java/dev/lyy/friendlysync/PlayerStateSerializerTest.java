package dev.lyy.friendlysync;

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
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.serialization.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlayerStateSerializerTest {

    private PlayerStateSerializer serializer;

    @BeforeEach
    void setUp() {
        CompressionService compressionService = new CompressionService(CompressionAlgorithm.GZIP, 0);
        serializer = new DefaultPlayerStateSerializer(compressionService);
    }

    @Test
    @DisplayName("Serialization: Complete snapshot serializes and deserializes with exact field fidelity")
    void testSerializationRoundTrip() {
        UUID playerUuid = UUID.randomUUID();
        long revision = 42;
        long fencingToken = 10;
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());

        byte[][] storage = new byte[36][];
        storage[0] = new byte[]{1, 2, 3, 4};
        storage[5] = new byte[]{5, 6, 7, 8};

        byte[][] armor = new byte[4][];
        armor[3] = new byte[]{9, 10, 11};

        InventorySnapshot inv = new InventorySnapshot(storage, armor, new byte[]{12, 13}, 4, new byte[]{14, 15});
        EnderChestSnapshot ec = new EnderChestSnapshot(new byte[27][]);
        ExperienceSnapshot xp = new ExperienceSnapshot(30, 0.75f, 1395);
        VitalSnapshot vitals = new VitalSnapshot(18.5, 20.0, 4.0);
        HungerSnapshot hunger = new HungerSnapshot(19, 5.0f, 0.2f);
        List<EffectSnapshot> effects = List.of(new EffectSnapshot("minecraft:speed", 600, 1, false, true, true));
        GameModeSnapshot gm = new GameModeSnapshot("SURVIVAL");
        FlightSnapshot flight = new FlightSnapshot(false, false, 0, 300, 0.0f);
        LocationSnapshot loc = new LocationSnapshot("world", 100.5, 64.0, -200.5, 90.0f, 0.0f);
        Map<String, byte[]> custom = Map.of("coins_plugin", new byte[]{0, 0, 0, 100});

        PlayerStateSnapshot original = new PlayerStateSnapshot(
            playerUuid, revision, fencingToken, inv, ec, xp, vitals, hunger,
            effects, gm, flight, loc, custom, now, PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
        );

        byte[] serialized = serializer.serialize(original);
        assertThat(serialized).isNotEmpty();

        PlayerStateSnapshot restored = serializer.deserialize(serialized);
        assertThat(restored.playerUuid()).isEqualTo(playerUuid);
        assertThat(restored.revision()).isEqualTo(revision);
        assertThat(restored.fencingToken()).isEqualTo(fencingToken);
        assertThat(restored.inventory()).isEqualTo(inv);
        assertThat(restored.experience()).isEqualTo(xp);
        assertThat(restored.vitals()).isEqualTo(vitals);
        assertThat(restored.hunger()).isEqualTo(hunger);
        assertThat(restored.effects()).isEqualTo(effects);
        assertThat(restored.gameMode()).isEqualTo(gm);
        assertThat(restored.flight()).isEqualTo(flight);
        assertThat(restored.location()).isEqualTo(loc);
        assertThat(restored.customModuleData()).containsEntry("coins_plugin", new byte[]{0, 0, 0, 100});
    }

    @Test
    @DisplayName("Serialization: Schema V3 snapshot with PDC and Statistics round-trips with full fidelity")
    void testSchemaV3WithPdcAndStatisticsRoundTrip() {
        UUID playerUuid = UUID.randomUUID();
        long revision = 100;
        long fencingToken = 50;
        Instant now = Instant.now();

        InventorySnapshot inv = new InventorySnapshot(new byte[36][], new byte[4][], new byte[0], 0, new byte[0]);
        EnderChestSnapshot ec = new EnderChestSnapshot(new byte[27][]);
        ExperienceSnapshot xp = new ExperienceSnapshot(10, 0.5f, 500);
        VitalSnapshot vitals = new VitalSnapshot(20.0, 20.0, 0.0);
        HungerSnapshot hunger = new HungerSnapshot(20, 5.0f, 0.0f);
        List<EffectSnapshot> effects = List.of();
        GameModeSnapshot gm = new GameModeSnapshot("SURVIVAL");
        FlightSnapshot flight = new FlightSnapshot(false, false, 0, 300, 0.0f);
        LocationSnapshot loc = new LocationSnapshot("world", 10.0, 64.0, 10.0, 0.0f, 0.0f);

        dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot pdc = new dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot(Map.of(
            new dev.lyy.friendlysync.model.pdc.PersistentDataKey("custom", "score"), new dev.lyy.friendlysync.model.pdc.PersistentDataValue.IntegerValue(9999),
            new dev.lyy.friendlysync.model.pdc.PersistentDataKey("custom", "tag"), new dev.lyy.friendlysync.model.pdc.PersistentDataValue.StringValue("VIP")
        ));

        dev.lyy.friendlysync.model.stats.StatisticsSnapshot stats = new dev.lyy.friendlysync.model.stats.StatisticsSnapshot(Map.of(
            dev.lyy.friendlysync.model.stats.StatisticKey.untyped("minecraft:deaths"), 5L,
            dev.lyy.friendlysync.model.stats.StatisticKey.material("minecraft:mine_block", "minecraft:iron_ore"), 250L
        ));

        PlayerStateSnapshot original = new PlayerStateSnapshot(
            playerUuid, revision, fencingToken, inv, ec, xp, vitals, hunger,
            effects, gm, flight, loc, pdc, stats, Map.of(), now, PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
        );

        byte[] serialized = serializer.serialize(original);
        assertThat(serialized).isNotEmpty();

        PlayerStateSnapshot restored = serializer.deserialize(serialized);
        assertThat(restored.persistentData()).isEqualTo(pdc);
        assertThat(restored.statistics()).isEqualTo(stats);
        assertThat(((dev.lyy.friendlysync.model.pdc.PersistentDataValue.IntegerValue) restored.persistentData().get(new dev.lyy.friendlysync.model.pdc.PersistentDataKey("custom", "score"))).value()).isEqualTo(9999);
        assertThat(restored.statistics().getOrDefault(dev.lyy.friendlysync.model.stats.StatisticKey.untyped("minecraft:deaths"), 0L)).isEqualTo(5L);
        assertThat(restored.statistics().getOrDefault(dev.lyy.friendlysync.model.stats.StatisticKey.material("minecraft:mine_block", "minecraft:iron_ore"), 0L)).isEqualTo(250L);
    }

    @Test
    @DisplayName("Corruption Detection: Tampered payload is rejected during deserialization")
    void testCorruptPayloadThrows() {
        byte[] corrupted = new byte[]{0, 0, 0, 0, 5, 1, 2, 3}; // bad magic header
        assertThatThrownBy(() -> serializer.deserialize(corrupted))
            .isInstanceOf(SerializationException.class);
    }
}
