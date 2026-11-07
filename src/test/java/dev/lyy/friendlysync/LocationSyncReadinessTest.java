package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.LocationSnapshot;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.service.FriendlySyncApiImpl;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.sync.module.LocationSyncModule;
import dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocationSyncReadinessTest {
    @Test
    void activeSessionRemainsUnreadyUntilItsExplicitJoinLockIsReleased() {
        UUID uuid = UUID.randomUUID();
        var sessions = mock(PlayerSessionService.class);
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "end", 1, 0, "Steve", SessionState.ACTIVE);
        when(sessions.getLocalSession(uuid)).thenReturn(Optional.of(session));
        when(sessions.getLocalSessionRaw(uuid)).thenReturn(session);
        var locks = new dev.lyy.friendlysync.lock.JoinLockService(sessions);
        var api = new FriendlySyncApiImpl(sessions, mock(PlayerStateService.class),
                mock(PlayerStateRepository.class), mock(SyncModuleManager.class),
                mock(DirtyTrackingService.class), mock(PlayerStateSerializer.class), locks);
        locks.setExplicitlyLocked(uuid, true);
        assertFalse(api.isPlayerReady(uuid), "ACTIVE with an explicit join lock must not release Core actions");
        locks.remove(uuid);
        assertTrue(api.isPlayerReady(uuid));
        session.forceState(SessionState.LOADING);
        assertFalse(api.isPlayerReady(uuid));
    }

    @Test
    void missingOverworldKeepsNativeEndArrivalInsteadOfForeignCoordinates() {
        var location = new LocationSyncModule();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        World end = mock(World.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getWorlds).thenReturn(java.util.List.of(end));
            location.apply(player, new LocationSnapshot("world", 12000, -40, -12000, 0, 0));
            verify(player, never()).teleportAsync(any(Location.class));
            assertFalse(location.isTeleportPending(player.getUniqueId()));
        }
    }

    @Test
    void sameNamedEndRestoresCoordinatesAndPreservesReadinessTracking() {
        var location = new LocationSyncModule();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var teleport = new CompletableFuture<Boolean>();
        when(player.teleportAsync(any(Location.class))).thenReturn(teleport);
        World end = mock(World.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(end);
            location.apply(player, new LocationSnapshot("world_the_end", 40.5, 65, 0.5, 90, 0));
            verify(player).teleportAsync(new Location(end, 40.5, 65, 0.5, 90, 0));
            assertTrue(location.isTeleportPending(player.getUniqueId()));
            teleport.complete(true);
            assertFalse(location.isTeleportPending(player.getUniqueId()));
        }
    }
    @Test
    void activeSessionRemainsUnreadyUntilAllLocationTeleportsFinish() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(mock(PersistentDataPolicy.class));
        var modules = new SyncModuleManager(config);
        var locationField = SyncModuleManager.class.getDeclaredField("locationModule");
        locationField.setAccessible(true);
        var location = (LocationSyncModule) locationField.get(modules);
        UUID uuid = UUID.randomUUID();
        var sessions = mock(PlayerSessionService.class);
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "target", 1, 0, "Steve", SessionState.ACTIVE);
        when(sessions.getLocalSession(uuid)).thenReturn(Optional.of(session));
        var api = new FriendlySyncApiImpl(sessions, mock(PlayerStateService.class),
                mock(PlayerStateRepository.class), modules, mock(DirtyTrackingService.class), mock(PlayerStateSerializer.class));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        var first = new CompletableFuture<Boolean>();
        var second = new CompletableFuture<Boolean>();
        when(player.teleportAsync(any(Location.class))).thenReturn(first, second);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
            assertTrue(api.isPlayerReady(uuid));
            var saved = new LocationSnapshot("world", 100, 64, 200, 0, 0);
            location.apply(player, saved);
            assertFalse(api.isPlayerReady(uuid));
            location.apply(player, saved);
            first.complete(true);
            assertFalse(api.isPlayerReady(uuid), "An older callback must not release the newer teleport");
            second.completeExceptionally(new IllegalStateException("Chunk load failed"));
            assertTrue(api.isPlayerReady(uuid));
            assertFalse(modules.isLocationTeleportPending(uuid));
            var older = new CompletableFuture<Boolean>();
            var newer = new CompletableFuture<Boolean>();
            when(player.teleportAsync(any(Location.class))).thenReturn(older, newer);
            location.apply(player, saved);
            location.apply(player, saved);
            newer.complete(true);
            assertFalse(api.isPlayerReady(uuid), "A delayed older teleport could still overwrite an RTP landing");
            older.complete(false);
            assertTrue(api.isPlayerReady(uuid));
            session.forceState(SessionState.LOADING);
            assertFalse(api.isPlayerReady(uuid));
        }
    }
}
