package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerStateServiceRevisionQueueTest {

    @Test
    void deferredCaptureRejectsLoadFailureAfterSaveWasRequested() throws Exception {
        UUID uuid = UUID.randomUUID();
        var sessions = new PlayerSessionService();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 4, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        var repository = mock(PlayerStateRepository.class);
        var serializer = mock(PlayerStateSerializer.class);
        var modules = mock(SyncModuleManager.class);
        var scheduler = mock(PlatformScheduler.class);
        var awaitingTick = new java.util.concurrent.CountDownLatch(1);
        var deferred = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        org.mockito.Mockito.doAnswer(inv -> { deferred.set(inv.getArgument(0)); awaitingTick.countDown(); return null; })
            .when(scheduler).runGlobal(any());
        org.mockito.Mockito.doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        var player = mock(org.bukkit.entity.Player.class);
        when(player.isOnline()).thenReturn(true);
        when(serializer.serialize(any())).thenReturn(new byte[] {1});
        when(repository.commit(any(), any(), anyLong(), anyLong(), any(), anyInt(), anyString()))
            .thenReturn(new CommitResult.Success(5, 1));
        try (var worker = Executors.newFixedThreadPool(2);
             var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(uuid)).thenReturn(player);
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(worker),
                modules, sessions, scheduler, worker, null);
            var save = service.saveState(uuid, dev.lyy.friendlysync.api.SyncReason.MANUAL, true);
            assertThat(awaitingTick.await(2, TimeUnit.SECONDS)).isTrue();
            session.markLoadFailed();
            session.forceState(SessionState.FAILED);
            deferred.get().run();
            assertThat(save.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Failed.class);
            org.mockito.Mockito.verifyNoInteractions(modules);
        }
    }

    @Test
    void failedLoadRejectsForcedAndDirectSavesWithoutTouchingStorage() throws Exception {
        UUID uuid = UUID.randomUUID();
        var sessions = new PlayerSessionService();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 4, "Player", SessionState.FAILED);
        session.markLoadFailed();
        sessions.registerSession(session);
        var repository = mock(PlayerStateRepository.class);
        var serializer = mock(PlayerStateSerializer.class);
        try (var worker = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(worker),
                mock(SyncModuleManager.class), sessions, mock(PlatformScheduler.class), worker, null);
            assertThat(service.saveState(uuid, dev.lyy.friendlysync.api.SyncReason.QUIT, true).get(2, TimeUnit.SECONDS))
                .isInstanceOf(CommitResult.Failed.class);
            assertThat(service.commitDirect(mock(PlayerStateSnapshot.class), session.toIdentity()).get(2, TimeUnit.SECONDS))
                .isInstanceOf(CommitResult.Failed.class);
            org.mockito.Mockito.verifyNoInteractions(repository, serializer);
        }
    }

    @Test
    void shutdownUnblocksPendingCaptureAndAllowsFinalDirectCommit() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerSessionService sessions = new PlayerSessionService();
        ActivePlayerSession session = new ActivePlayerSession(
            uuid, UUID.randomUUID(), "source", 1, 0, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        PlayerStateRepository repository = mock(PlayerStateRepository.class);
        when(repository.commit(any(), any(), anyLong(), anyLong(), any(), anyInt(), anyString()))
            .thenReturn(new CommitResult.Success(1, 1));
        PlayerStateSerializer serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(new byte[] {1});
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        java.util.concurrent.CountDownLatch awaitingTick = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> deferred = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            deferred.set(inv.getArgument(0));
            awaitingTick.countDown();
            return null;
        }).when(scheduler).runGlobal(any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PlayerStateService service = new PlayerStateService(repository, serializer,
                new PlayerOperationQueue(executor), mock(SyncModuleManager.class),
                sessions, scheduler, executor, null);
            var checkpoint = service.saveState(uuid, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, true);
            assertThat(awaitingTick.await(2, TimeUnit.SECONDS)).isTrue();
            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                uuid, 0, 1, null, null, null, null, null, List.of(), null, null, null,
                null, null, Map.of(), Instant.now(), PlayerStateSnapshot.CURRENT_SCHEMA_VERSION);
            var finalCommit = service.commitDirect(snapshot, session.toIdentity());
            service.beginShutdown();
            assertThat(checkpoint.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Failed.class);
            assertThat(finalCommit.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            deferred.get().run();
            org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never()).runForPlayer(any(), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queuedDirectCommitsUseRevisionProducedByPreviousCommit() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PlayerSessionService sessions = new PlayerSessionService();
        ActivePlayerSession active = new ActivePlayerSession(
            playerId, sessionId, "spawn-a", 13, 8, "QueuedPlayer", SessionState.ACTIVE
        );
        sessions.registerSession(active);

        PlayerStateRepository repository = mock(PlayerStateRepository.class);
        List<Long> expectedRevisions = new CopyOnWriteArrayList<>();
        when(repository.commit(any(), any(), anyLong(), anyLong(), any(), anyInt(), anyString()))
            .thenAnswer(invocation -> {
                long expectedRevision = invocation.getArgument(3, Long.class);
                expectedRevisions.add(expectedRevision);
                return new CommitResult.Success(expectedRevision + 1, 1);
            });

        PlayerStateSerializer serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(new byte[] {1, 2, 3});

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PlayerOperationQueue queue = new PlayerOperationQueue(executor);
            PlayerStateService service = new PlayerStateService(
                repository,
                serializer,
                queue,
                mock(SyncModuleManager.class),
                sessions,
                mock(PlatformScheduler.class),
                executor,
                null
            );
            PlayerStateSnapshot snapshotAtRevisionEight = new PlayerStateSnapshot(
                playerId, 8, 13, null, null, null, null, null, List.of(),
                null, null, null, null, null, Map.of(), Instant.now(),
                PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
            );
            SessionIdentity identity = active.toIdentity();

            var first = service.commitDirect(snapshotAtRevisionEight, identity);
            var second = service.commitDirect(snapshotAtRevisionEight, identity);

            assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            assertThat(second.get(5, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            assertThat(expectedRevisions).containsExactly(8L, 9L);
            assertThat(active.getRevision()).isEqualTo(10L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void commitDirectFastForwardsRevisionWhenConflictHasMatchingTokenAndHigherRevision() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PlayerSessionService sessions = new PlayerSessionService();
        ActivePlayerSession active = new ActivePlayerSession(
            playerId, sessionId, "spawn-a", 13, 5, "ConflictPlayer", SessionState.ACTIVE
        );
        sessions.registerSession(active);

        PlayerStateRepository repository = mock(PlayerStateRepository.class);
        when(repository.commit(eq(playerId), eq(sessionId), eq(13L), eq(5L), any(), anyInt(), anyString(), anyString(), anyString()))
            .thenReturn(new CommitResult.Conflict(10, 13, "DB revision ahead"));

        PlayerStateSerializer serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(new byte[] {1, 2, 3});

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlayerOperationQueue queue = new PlayerOperationQueue(executor);
            PlayerStateService service = new PlayerStateService(
                repository,
                serializer,
                queue,
                mock(SyncModuleManager.class),
                sessions,
                mock(PlatformScheduler.class),
                executor,
                null
            );

            PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                playerId, 5, 13, null, null, null, null, null, List.of(),
                null, null, null, null, null, Map.of(), Instant.now(),
                PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
            );

            var result = service.commitDirect(snapshot, active.toIdentity());
            assertThat(result.get(5, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Conflict.class);

            // ActivePlayerSession revision must be fast-forwarded to 10
            assertThat(active.getRevision()).isEqualTo(10L);
        } finally {
            executor.shutdownNow();
        }
    }
}



