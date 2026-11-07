package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CheckpointTrafficTest {
    @Test void fortyDirtyPlayersSpreadAcrossIntervalWithoutPerActionTimers() {
        var sessions = new PlayerSessionService();
        var clock = new AtomicLong();
        var scheduler = mock(PlatformScheduler.class);
        var saves = new ArrayList<UUID>();
        var service = new DirtyTrackingService(sessions, saves::add, scheduler, Duration.ofSeconds(2), clock::get);
        for (int i = 0; i < 40; i++) {
            UUID uuid = new UUID(1, i * 750_000_000L);
            sessions.registerSession(new ActivePlayerSession(uuid, UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE));
            for (int mutation = 0; mutation < 1000; mutation++) service.markDirtyForCheckpoint(uuid);
        }
        verifyNoInteractions(scheduler);
        for (int second = 0; second <= 30; second++) {
            clock.set(Duration.ofSeconds(second).toNanos());
            int before = saves.size();
            service.runCheckpointSlice(Duration.ofSeconds(30));
            assertThat(saves.size() - before).as("uploads submitted in one slice").isLessThanOrEqualTo(2);
        }
        assertThat(new HashSet<>(saves)).hasSize(40);
        // Failed/in-flight saves leave mutations dirty and remain eligible for a later retry.
        assertThat(sessions.getAllLocalSessions()).allMatch(ActivePlayerSession::isDirty);
        assertThat(sessions.getAllLocalSessions()).allMatch(s -> s.getDirtyGeneration() == 1000);
    }

    @Test void cleanAndReplacedSessionsDoNotReceiveOldScheduledCheckpoint() {
        var sessions = new PlayerSessionService();
        var clock = new AtomicLong();
        var saves = new ArrayList<UUID>();
        var service = new DirtyTrackingService(sessions, saves::add, null, Duration.ofSeconds(2), clock::get);
        UUID uuid = new UUID(0, Duration.ofSeconds(10).toNanos());
        var old = new ActivePlayerSession(uuid, UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE);
        sessions.registerSession(old);
        service.markDirtyForCheckpoint(uuid);
        service.runCheckpointSlice(Duration.ofSeconds(30));
        var replacement = new ActivePlayerSession(uuid, UUID.randomUUID(), "spawn-a", 2, 0, "Player", SessionState.ACTIVE);
        sessions.registerSession(replacement);
        clock.set(Duration.ofSeconds(10).toNanos());
        service.runCheckpointSlice(Duration.ofSeconds(30));
        assertThat(saves).isEmpty();
        service.markDirtyForCheckpoint(uuid);
        clock.set(Duration.ofSeconds(20).toNanos());
        service.runCheckpointSlice(Duration.ofSeconds(30));
        assertThat(saves).containsExactly(uuid);
    }

    @Test void checkpointStateDeduplicationSkipsDatabaseCommitWhenUnchanged() throws Exception {
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);

        var player = mock(org.bukkit.entity.Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(uuid);

        var previousSnapshot = new PlayerStateSnapshot(uuid, 0, 1, null, null, null, null, null,
            List.of(), null, null, null, null, null, Map.of(), java.time.Instant.now(), 3);
        session.setLastCommittedSnapshot(previousSnapshot);
        session.markDirty();
        assertThat(session.isDirty()).isTrue();

        var modules = mock(dev.lyy.friendlysync.sync.SyncModuleManager.class);
        when(modules.captureSnapshot(eq(player), anyLong(), anyLong(), eq(dev.lyy.friendlysync.api.SyncReason.CHECKPOINT), eq(session)))
            .thenReturn(previousSnapshot);

        var repository = mock(dev.lyy.friendlysync.database.repository.PlayerStateRepository.class);
        var serializer = mock(dev.lyy.friendlysync.serialization.PlayerStateSerializer.class);
        var scheduler = mock(PlatformScheduler.class);

        doAnswer(call -> {
            Runnable r = call.getArgument(0);
            r.run();
            return null;
        }).when(scheduler).runGlobal(any());

        doAnswer(call -> {
            Runnable r = call.getArgument(1);
            r.run();
            return null;
        }).when(scheduler).runForPlayer(any(), any(), any());

        doAnswer(call -> {
            Runnable r = call.getArgument(0);
            r.run();
            return null;
        }).when(scheduler).runAsync(any());

        var queue = new dev.lyy.friendlysync.scheduler.PlayerOperationQueue(Runnable::run);
        var service = new dev.lyy.friendlysync.session.PlayerStateService(repository, serializer, queue, modules, sessions, scheduler);

        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(uuid)).thenReturn(player);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));

            var resultFuture = service.saveState(uuid, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, false);
            var result = resultFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(dev.lyy.friendlysync.model.result.CommitResult.Success.class);
            // Crucial: repository.commit must NOT be called because state is identical
            verifyNoInteractions(repository);
            verifyNoInteractions(serializer);
            // Session dirty state should now be cleared
            assertThat(session.isDirty()).isFalse();
        }
    }

    @Test void checkpointReusesCachedEnderChestWhenNotDirty() {
        var config = mock(dev.lyy.friendlysync.config.FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
            false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16
        ));
        when(config.isModuleEnabled("ender-chest")).thenReturn(true);
        when(config.isModuleEnabled(argThat(name -> !"ender-chest".equals(name)))).thenReturn(false);

        var manager = new dev.lyy.friendlysync.sync.SyncModuleManager(config);
        var session = new ActivePlayerSession(UUID.randomUUID(), UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE);

        var cachedEc = new EnderChestSnapshot(new byte[27][]);
        session.cacheEnderChest(cachedEc);
        assertThat(session.isEnderChestDirty()).isFalse();

        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(session.getPlayerUuid());

        // Under CHECKPOINT reason and clean ender chest, cached instance is reused
        var snapshot = manager.captureSnapshot(player, 0, 1, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, session);
        assertThat(snapshot.enderChest()).isSameAs(cachedEc);

        // Under non-checkpoint reason or dirty ender chest, player inventory is touched
        session.markEnderChestDirty();
        assertThat(session.isEnderChestDirty()).isTrue();
        var playerEcInv = mock(org.bukkit.inventory.Inventory.class);
        when(playerEcInv.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[27]);
        when(playerEcInv.getSize()).thenReturn(27);
        when(player.getEnderChest()).thenReturn(playerEcInv);

        var snapshotDirty = manager.captureSnapshot(player, 0, 1, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, session);
        assertThat(snapshotDirty.enderChest()).isNotSameAs(cachedEc);
        assertThat(session.isEnderChestDirty()).isFalse();
    }

    @Test void checkpointReusesCachedInventoryWhenNotDirty() {
        var config = mock(dev.lyy.friendlysync.config.FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
            false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16
        ));
        when(config.isModuleEnabled("inventory")).thenReturn(true);
        when(config.isModuleEnabled(argThat(name -> !"inventory".equals(name)))).thenReturn(false);

        var manager = new dev.lyy.friendlysync.sync.SyncModuleManager(config);
        var session = new ActivePlayerSession(UUID.randomUUID(), UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE);

        var cachedInv = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        session.cacheInventory(cachedInv);
        assertThat(session.isInventoryDirty()).isFalse();

        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(session.getPlayerUuid());

        // Under CHECKPOINT reason and clean inventory, cached instance is reused
        var snapshot = manager.captureSnapshot(player, 0, 1, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, session);
        assertThat(snapshot.inventory()).isSameAs(cachedInv);

        // When inventory is marked dirty, live inventory is captured and cached
        session.markInventoryDirty();
        assertThat(session.isInventoryDirty()).isTrue();
        var playerInv = mock(org.bukkit.inventory.PlayerInventory.class);
        when(playerInv.getStorageContents()).thenReturn(new org.bukkit.inventory.ItemStack[36]);
        when(playerInv.getArmorContents()).thenReturn(new org.bukkit.inventory.ItemStack[4]);
        when(player.getInventory()).thenReturn(playerInv);

        var snapshotDirty = manager.captureSnapshot(player, 0, 1, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, session);
        assertThat(snapshotDirty.inventory()).isNotSameAs(cachedInv);
        assertThat(session.isInventoryDirty()).isFalse();
    }

    @Test void checkpointStateDeduplicationPreservesPotionDurationAndExhaustionChanges() {
        UUID uuid = UUID.randomUUID();
        var effect1 = new EffectSnapshot("SPEED", 3600, 1, false, false, false);
        var effect2 = new EffectSnapshot("SPEED", 3540, 1, false, false, false); // 3 seconds ticked down

        var hunger1 = new HungerSnapshot(20, 5.0f, 1.25f);
        var hunger2 = new HungerSnapshot(20, 5.0f, 2.10f); // exhaustion changed while sprinting

        var vitals1 = new VitalSnapshot(20.0, 20.0, 0.0);
        var vitals2 = new VitalSnapshot(20.0, 20.0, 0.0);

        var snapshot1 = new PlayerStateSnapshot(uuid, 1, 1, null, null, null, vitals1, hunger1,
            List.of(effect1), null, null, null, null, null, Map.of(), java.time.Instant.now(), 3);
        var snapshot2 = new PlayerStateSnapshot(uuid, 2, 1, null, null, null, vitals2, hunger2,
            List.of(effect2), null, null, null, null, null, Map.of(), java.time.Instant.now(), 3);

        assertThat(snapshot2.hasIdenticalState(snapshot1)).isFalse();
    }

    @Test void checkpointComparisonUsesCustomBytesAndIncludesLocationStatisticsAndEffectFlags() {
        UUID uuid = UUID.randomUUID();
        var location = new LocationSnapshot("world", 1, 2, 3, 0, 0);
        var stats = new dev.lyy.friendlysync.model.stats.StatisticsSnapshot(Map.of(
            dev.lyy.friendlysync.model.stats.StatisticKey.untyped("jump"), 1L));
        var effect = new EffectSnapshot("SPEED", 100, 1, false, false, false);
        var baseline = comparisonSnapshot(uuid, location, stats, effect, Map.of("custom", new byte[] {1, 2}));
        assertThat(comparisonSnapshot(uuid, location, stats, effect, Map.of("custom", new byte[] {1, 2}))
            .hasIdenticalState(baseline)).isTrue();
        assertThat(comparisonSnapshot(uuid, location, stats, effect, Map.of("custom", new byte[] {1, 3}))
            .hasIdenticalState(baseline)).isFalse();
        assertThat(comparisonSnapshot(uuid, location, stats, effect, Map.of("different", new byte[] {1, 2}))
            .hasIdenticalState(baseline)).isFalse();
        assertThat(comparisonSnapshot(uuid, new LocationSnapshot("world", 4, 2, 3, 0, 0), stats, effect,
            baseline.customModuleData()).hasIdenticalState(baseline)).isFalse();
        assertThat(comparisonSnapshot(uuid, location, dev.lyy.friendlysync.model.stats.StatisticsSnapshot.EMPTY,
            effect, baseline.customModuleData()).hasIdenticalState(baseline)).isFalse();
        assertThat(comparisonSnapshot(uuid, location, stats, new EffectSnapshot("SPEED", 100, 1, true, true, true),
            baseline.customModuleData()).hasIdenticalState(baseline)).isFalse();
    }

    private PlayerStateSnapshot comparisonSnapshot(UUID uuid, LocationSnapshot location,
            dev.lyy.friendlysync.model.stats.StatisticsSnapshot stats, EffectSnapshot effect, Map<String, byte[]> custom) {
        return new PlayerStateSnapshot(uuid, 0, 1, null, null, null, null, null, List.of(effect),
            null, null, location, null, stats, custom, java.time.Instant.now(), 3);
    }

    @Test void markDirtyMarksInventoryDirtyForCheckpointCapture() {
        var sessionService = mock(dev.lyy.friendlysync.session.PlayerSessionService.class);
        var session = new ActivePlayerSession(UUID.randomUUID(), UUID.randomUUID(), "spawn-a", 1, 0, "Player", SessionState.ACTIVE);
        when(sessionService.getLocalSessionRaw(session.getPlayerUuid())).thenReturn(session);

        var dirtyTrackingService = new dev.lyy.friendlysync.sync.DirtyTrackingService(sessionService, uuid -> {});
        session.clearInventoryDirty();
        assertThat(session.isInventoryDirty()).isFalse();

        dirtyTrackingService.markDirty(session.getPlayerUuid());
        assertThat(session.isInventoryDirty()).isTrue();
    }
}
