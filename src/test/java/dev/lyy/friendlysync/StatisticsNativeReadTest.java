package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.sync.stats.StatisticsCaptureService;
import dev.lyy.friendlysync.sync.stats.StatisticsRegistry;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.craftbukkit.CraftStatistic.Stat;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StatisticsNativeReadTest {

    private static final StatisticsRegistry REGISTRY = new StatisticsRegistry();

    private static CraftPlayer player(Map<Object, Integer> stats) {
        CraftPlayer player = mock(CraftPlayer.class);
        CraftPlayer.Handle handle = new CraftPlayer.Handle(stats);
        when(player.getHandle()).thenReturn(handle);
        return player;
    }

    @Test
    void nativeReadMatchesSweepThenReplacesIt() {
        CraftPlayer player = player(Map.of(
            new Stat(Statistic.DEATHS, null, null), 12,
            new Stat(Statistic.BREAK_ITEM, Material.DIAMOND_SWORD, null), 40,
            new Stat(Statistic.KILL_ENTITY, null, EntityType.ZOMBIE), 3,
            new Stat(Statistic.JUMP, null, null), 0,
            new Stat(null, null, null), 7));
        when(player.getStatistic(Statistic.DEATHS)).thenReturn(12);
        when(player.getStatistic(Statistic.BREAK_ITEM, Material.DIAMOND_SWORD)).thenReturn(40);
        when(player.getStatistic(Statistic.KILL_ENTITY, EntityType.ZOMBIE)).thenReturn(3);
        Map<StatisticKey, Long> expected = Map.of(
            StatisticKey.untyped("minecraft:deaths"), 12L,
            StatisticKey.material("minecraft:break_item", "minecraft:diamond_sword"), 40L,
            StatisticKey.entity("minecraft:kill_entity", "minecraft:zombie"), 3L);
        StatisticsCaptureService service = new StatisticsCaptureService(REGISTRY);

        for (int i = 0; i < 3; i++) {
            assertEquals(expected, service.capture(player).values());
        }
        clearInvocations(player);

        assertEquals(expected, service.capture(player).values());
        verify(player, never()).getStatistic(any(Statistic.class));
        verify(player, never()).getStatistic(any(Statistic.class), any(Material.class));
    }

    @Test
    void mismatchKeepsUsingTheSweep() {
        CraftPlayer player = player(Map.of(new Stat(Statistic.DEATHS, null, null), 5));
        when(player.getStatistic(Statistic.DEATHS)).thenReturn(4);
        StatisticsCaptureService service = new StatisticsCaptureService(REGISTRY);

        for (int i = 0; i < 5; i++) {
            assertEquals(Map.of(StatisticKey.untyped("minecraft:deaths"), 4L), service.capture(player).values());
        }
        verify(player, times(5)).getStatistic(Statistic.DEATHS);
    }
}
