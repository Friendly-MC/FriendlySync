package dev.lyy.friendlysync;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.*;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.metrics.MetricsService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisTransferStashTest {

    @Test
    @DisplayName("Transfer stash loads directly into memory with SHA-256 match, bypassing MySQL LONGBLOB query")
    void transferStashLoadedWhenMetadataAndHashMatch() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] stashedBytes = {10, 20, 30, 40};
        String hash = DigestUtils.sha256(stashedBytes);

        var repository = mock(PlayerStateRepository.class);
        when(repository.findMetadata(uuid)).thenReturn(Optional.of(
            new PlayerStateMetadata(5, 2, UUID.randomUUID(), "spawn-a", hash)
        ));

        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.deserialize(stashedBytes)).thenReturn(snapshot(uuid, 5));

        var messaging = mock(MessagingService.class);
        when(messaging.retrieveTransferPayload(uuid)).thenReturn(stashedBytes);

        var config = mock(FriendlySyncConfig.class);
        when(config.isTransferStashEnabled()).thenReturn(true);

        var metrics = new MetricsService();

        try (var workers = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(
                repository, serializer, new PlayerOperationQueue(workers),
                mock(SyncModuleManager.class), new PlayerSessionService(),
                mock(PlatformScheduler.class), workers, metrics, messaging, config
            );

            var result = service.loadState(uuid).get(2, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(LoadResult.Success.class);
            assertThat(((LoadResult.Success) result).snapshot().revision()).isEqualTo(5);
            assertThat(metrics.getTotalStashHits()).isEqualTo(1);
            assertThat(metrics.getTotalStashMisses()).isEqualTo(0);

            // Crucial assertion: repository.find(uuid) which loads the heavy LONGBLOB is never called!
            verify(repository, never()).find(uuid);
        }
    }

    @Test
    @DisplayName("Hash mismatch in transfer stash safely falls back to authoritative SQL")
    void stashFallbackToSqlWhenHashMismatched() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] stashedBytes = {10, 20, 30, 40};
        byte[] sqlBytes = {50, 60, 70, 80};
        String sqlHash = DigestUtils.sha256(sqlBytes);

        var repository = mock(PlayerStateRepository.class);
        // Metadata has sqlHash, which does not match stashedBytes hash!
        when(repository.findMetadata(uuid)).thenReturn(Optional.of(
            new PlayerStateMetadata(5, 2, UUID.randomUUID(), "spawn-a", sqlHash)
        ));
        when(repository.find(uuid)).thenReturn(Optional.of(row(uuid, sqlBytes, sqlHash, 5)));

        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.deserialize(sqlBytes)).thenReturn(snapshot(uuid, 5));

        var messaging = mock(MessagingService.class);
        when(messaging.retrieveTransferPayload(uuid)).thenReturn(stashedBytes);

        var config = mock(FriendlySyncConfig.class);
        when(config.isTransferStashEnabled()).thenReturn(true);

        var metrics = new MetricsService();

        try (var workers = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(
                repository, serializer, new PlayerOperationQueue(workers),
                mock(SyncModuleManager.class), new PlayerSessionService(),
                mock(PlatformScheduler.class), workers, metrics, messaging, config
            );

            var result = service.loadState(uuid).get(2, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(LoadResult.Success.class);
            assertThat(((LoadResult.Success) result).snapshot().revision()).isEqualTo(5);
            assertThat(metrics.getTotalStashMisses()).isEqualTo(1);

            // Verified fallback to SQL!
            verify(repository).find(uuid);
        }
    }

    @Test
    @DisplayName("Empty transfer stash cleanly queries authoritative SQL")
    void stashFallbackToSqlWhenStashEmpty() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] sqlBytes = {50, 60, 70, 80};
        String sqlHash = DigestUtils.sha256(sqlBytes);

        var repository = mock(PlayerStateRepository.class);
        when(repository.find(uuid)).thenReturn(Optional.of(row(uuid, sqlBytes, sqlHash, 3)));

        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.deserialize(sqlBytes)).thenReturn(snapshot(uuid, 3));

        var messaging = mock(MessagingService.class);
        when(messaging.retrieveTransferPayload(uuid)).thenReturn(null);

        var config = mock(FriendlySyncConfig.class);
        when(config.isTransferStashEnabled()).thenReturn(true);

        var metrics = new MetricsService();

        try (var workers = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(
                repository, serializer, new PlayerOperationQueue(workers),
                mock(SyncModuleManager.class), new PlayerSessionService(),
                mock(PlatformScheduler.class), workers, metrics, messaging, config
            );

            var result = service.loadState(uuid).get(2, TimeUnit.SECONDS);

            assertThat(result).isInstanceOf(LoadResult.Success.class);
            assertThat(metrics.getTotalStashMisses()).isEqualTo(1);
            verify(repository).find(uuid);
        }
    }

    @Test
    @DisplayName("Transfer commit saves to SQL and stashes serialized payload in Redis with TTL")
    void transferCommitStashesPayloadInRedis() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(uuid, sessionId, "economy-a", 10L);
        var sessionService = new PlayerSessionService();
        ActivePlayerSession active = new ActivePlayerSession(uuid, sessionId, "economy-a", 10L, 2L, "Player", SessionState.ACTIVE);
        sessionService.registerSession(active);

        byte[] serialized = {1, 2, 3, 4};
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(serialized);

        var repository = mock(PlayerStateRepository.class);
        when(repository.commit(eq(uuid), eq(sessionId), eq(10L), eq(2L), eq(serialized), anyInt(), anyString(), anyString(), anyString()))
            .thenReturn(new CommitResult.Success(3L, 15L));

        var messaging = mock(MessagingService.class);
        var config = mock(FriendlySyncConfig.class);
        when(config.isTransferStashEnabled()).thenReturn(true);
        when(config.getTransferStashTtlSeconds()).thenReturn(60);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(
                repository, serializer, new PlayerOperationQueue(workers),
                mock(SyncModuleManager.class), sessionService,
                mock(PlatformScheduler.class), workers, new MetricsService(), messaging, config
            );

            var result = service.commitDirect(snapshot(uuid, 2), identity).get(2, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(CommitResult.Success.class);

            service.stashTransferPayload(uuid, snapshot(uuid, 2), 3L);
            verify(messaging).stashTransferPayload(uuid, serialized, 60);
        }
    }

    @Test
    @DisplayName("Coordinator quit clears transfer stash in Redis, preventing rejoin dupes")
    void quitClearsStashInRedis() throws Exception {
        var localSessions = new PlayerSessionService();
        var localOwnership = mock(dev.lyy.friendlysync.session.PlayerOwnershipService.class);
        when(localOwnership.getServerId()).thenReturn("source");
        var messaging = mock(MessagingService.class);
        var scheduler = mock(PlatformScheduler.class);
        var states = mock(PlayerStateService.class);
        var modules = mock(SyncModuleManager.class);

        var testCoordinator = new dev.lyy.friendlysync.session.PlayerSessionCoordinator(
            localOwnership, localSessions, states, modules,
            new dev.lyy.friendlysync.lock.JoinLockService(localSessions), scheduler, null, messaging, null
        );

        UUID uuid = UUID.randomUUID();
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Player");

        localSessions.registerSession(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L, "Player", SessionState.ACTIVE);
        when(states.commitDirect(any(), any())).thenReturn(CompletableFuture.completedFuture(new CommitResult.Success(2L, 0)));

        testCoordinator.handlePlayerQuit(player);

        verify(messaging, atLeastOnce()).clearTransferPayload(uuid);
        verify(messaging, never()).stashTransferPayload(any(), any(), anyInt());
    }

    private PersistedPlayerState row(UUID uuid, byte[] payload, String hash, long rev) {
        return new PersistedPlayerState(uuid, "Player", rev, 2, UUID.randomUUID(), "spawn-a", "LOADING",
            Instant.now().plusSeconds(45), payload, 3, hash, Instant.now(), Instant.now());
    }

    private PlayerStateSnapshot snapshot(UUID uuid, long revision) {
        return new PlayerStateSnapshot(uuid, revision, 1, null, null, null, null, null,
            List.of(), null, null, null, null, null, Map.of(), Instant.now(), 3);
    }
}
