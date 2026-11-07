package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.sync.stats.StatisticsCaptureService;
import dev.lyy.friendlysync.sync.stats.StatisticsApplyService;
import dev.lyy.friendlysync.sync.stats.StatisticsRegistry;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatisticsRegistryOptimizationTest {

    @Test
    void sparseReplacementClearsOldValuesAndSkipsUnchangedWrites() {
        StatisticsRegistry registry = mock(StatisticsRegistry.class);
        StatisticKey deaths = StatisticKey.untyped("minecraft:deaths");
        StatisticKey kills = StatisticKey.untyped("minecraft:player_kills");
        var deathDescriptor = new StatisticsRegistry.StatisticDescriptor(Statistic.DEATHS, Statistic.Type.UNTYPED,
            dev.lyy.friendlysync.model.stats.StatisticQualifierType.NONE, deaths, null, null);
        var killDescriptor = new StatisticsRegistry.StatisticDescriptor(Statistic.PLAYER_KILLS, Statistic.Type.UNTYPED,
            dev.lyy.friendlysync.model.stats.StatisticQualifierType.NONE, kills, null, null);
        when(registry.allDescriptors()).thenReturn(java.util.List.of(deathDescriptor, killDescriptor));
        when(registry.findByKey(deaths)).thenReturn(deathDescriptor);
        when(registry.findByKey(kills)).thenReturn(killDescriptor);
        Player player = mock(Player.class);
        var currentDeaths = new java.util.concurrent.atomic.AtomicInteger(12);
        when(player.getStatistic(Statistic.DEATHS)).thenAnswer(call -> currentDeaths.get());
        when(player.getStatistic(Statistic.PLAYER_KILLS)).thenReturn(3);
        doAnswer(call -> { currentDeaths.set(call.getArgument(1)); return null; })
            .when(player).setStatistic(eq(Statistic.DEATHS), anyInt());
        var apply = new StatisticsApplyService(registry);
        var snapshot = new StatisticsSnapshot(java.util.Map.of(kills, 3L));

        apply.apply(player, snapshot);
        apply.apply(player, snapshot);

        assertEquals(0, currentDeaths.get());
        verify(player, times(1)).setStatistic(Statistic.DEATHS, 0);
        verify(player, never()).setStatistic(eq(Statistic.PLAYER_KILLS), anyInt());
    }

    @Test
    void unexpectedCaptureFailuresPropagateWithoutPruningTheKey() {
        StatisticsRegistry registry = mock(StatisticsRegistry.class);
        StatisticKey key = StatisticKey.untyped("minecraft:deaths");
        when(registry.allDescriptors()).thenReturn(java.util.List.of(new StatisticsRegistry.StatisticDescriptor(
            Statistic.DEATHS, Statistic.Type.UNTYPED, dev.lyy.friendlysync.model.stats.StatisticQualifierType.NONE,
            key, null, null)));
        StatisticsCaptureService capture = new StatisticsCaptureService(registry);
        Player player = mock(Player.class);
        when(player.getStatistic(Statistic.DEATHS)).thenThrow(new IllegalStateException("temporary failure"));

        assertThrows(IllegalStateException.class, () -> capture.capture(player));
        assertFalse(capture.isKeyUnsupported(key));
        doReturn(7).when(player).getStatistic(Statistic.DEATHS);
        assertEquals(7L, capture.capture(player).values().get(key));
    }

    @Test
    void mineableCauldronsRemainInTheRegistry() {
        Material cauldron = mock(Material.class);
        when(cauldron.isBlock()).thenReturn(true);
        when(cauldron.isAir()).thenReturn(false);
        when(cauldron.isLegacy()).thenReturn(false);
        when(cauldron.name()).thenReturn("LAVA_CAULDRON");
        when(cauldron.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft("lava_cauldron"));
        try (var materials = mockStatic(Material.class)) {
            materials.when(Material::values).thenReturn(new Material[] {cauldron});
            StatisticsRegistry registry = new StatisticsRegistry();
            assertNotNull(registry.findByKey(StatisticKey.material("minecraft:mine_block", "minecraft:lava_cauldron")));
        }
    }

    @Test
    @DisplayName("StatisticsRegistry: Excludes BREAK_ITEM for items with zero max durability")
    void testBreakItemExcludedForNonDurabilityItems() {
        StatisticsRegistry registry = new StatisticsRegistry();

        // Material.DIRT has maxDurability == 0
        StatisticKey dirtBreakKey = StatisticKey.material("minecraft:break_item", "minecraft:dirt");
        assertNull(registry.findByKey(dirtBreakKey), "BREAK_ITEM on DIRT should not be in registry");

        // Material.DIAMOND_SWORD has maxDurability > 0
        StatisticKey swordBreakKey = StatisticKey.material("minecraft:break_item", "minecraft:diamond_sword");
        assertNotNull(registry.findByKey(swordBreakKey), "BREAK_ITEM on DIAMOND_SWORD should be in registry");

        // AIR and fluids should not be registered under MINE_BLOCK
        StatisticKey airMineKey = StatisticKey.material("minecraft:mine_block", "minecraft:air");
        assertNull(registry.findByKey(airMineKey), "MINE_BLOCK on AIR should not be in registry");
        StatisticKey waterMineKey = StatisticKey.material("minecraft:mine_block", "minecraft:water");
        assertNull(registry.findByKey(waterMineKey), "MINE_BLOCK on WATER should not be in registry");
    }

    @Test
    @DisplayName("StatisticsCaptureService: Caches unsupported statistic combinations after first failure")
    void testUnsupportedKeysCached() {
        StatisticsRegistry registry = new StatisticsRegistry();
        StatisticsCaptureService service = new StatisticsCaptureService(registry);

        Player mockPlayer = Mockito.mock(Player.class);

        // Simulate an illegal statistic combination throwing an IllegalArgumentException
        doThrow(new IllegalArgumentException("Item does not have durability!"))
                .when(mockPlayer).getStatistic(eq(Statistic.BREAK_ITEM), any(Material.class));

        // First capture encounters exceptions and caches unsupported keys
        service.capture(mockPlayer);

        // Find a BREAK_ITEM descriptor in registry
        StatisticsRegistry.StatisticDescriptor breakItemDesc = registry.allDescriptors().stream()
                .filter(d -> d.statistic() == Statistic.BREAK_ITEM)
                .findFirst()
                .orElse(null);

        if (breakItemDesc != null) {
            assertTrue(service.isKeyUnsupported(breakItemDesc.key()),
                    "Descriptor that threw an exception must be cached as unsupported");

            // Reset mock interactions
            Mockito.clearInvocations(mockPlayer);

            // Second capture should skip the cached unsupported keys without calling player.getStatistic() for them
            service.capture(mockPlayer);

            verify(mockPlayer, never()).getStatistic(eq(Statistic.BREAK_ITEM), eq(breakItemDesc.material()));
        }
    }
}
