package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticQualifierType;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.serialization.StatisticsSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsSerializationTest {

    @Test
    @DisplayName("Statistics: Empty snapshot round-trips cleanly")
    void testEmptyStatistics() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        StatisticsSerializer.writeSnapshot(dos, StatisticsSnapshot.EMPTY);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        StatisticsSnapshot restored = StatisticsSerializer.readSnapshot(dis);

        assertTrue(restored.isEmpty());
        assertEquals(0, restored.size());
    }

    @Test
    @DisplayName("Statistics: Untyped, material, and entity qualified statistics round-trip with exact fidelity")
    void testStatisticsRoundTrip() throws Exception {
        Map<StatisticKey, Long> stats = new HashMap<>();
        stats.put(StatisticKey.untyped("minecraft:deaths"), 12L);
        stats.put(StatisticKey.untyped("minecraft:player_kills"), 4L);
        stats.put(StatisticKey.material("minecraft:mine_block", "minecraft:diamond_ore"), 128L);
        stats.put(StatisticKey.material("minecraft:use_item", "minecraft:golden_apple"), 15L);
        stats.put(StatisticKey.entity("minecraft:kill_entity", "minecraft:zombie"), 250L);

        StatisticsSnapshot original = new StatisticsSnapshot(stats);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        StatisticsSerializer.writeSnapshot(dos, original);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        StatisticsSnapshot restored = StatisticsSerializer.readSnapshot(dis);

        assertEquals(original.size(), restored.size());
        assertEquals(original, restored);
        assertEquals(128L, restored.getOrDefault(StatisticKey.material("minecraft:mine_block", "minecraft:diamond_ore"), 0L));
    }
}
