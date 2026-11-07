package dev.lyy.friendlysync;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.messaging.message.TransferRequestMessage;
import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.*;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LifecycleSafetyTest {
    final UUID uuid = UUID.randomUUID();
    final Player player = mock(Player.class);
    final PlayerSessionService sessions = new PlayerSessionService();
    final ActivePlayerSession session = new ActivePlayerSession(
        uuid, UUID.randomUUID(), "source", 7, 12, "Player", SessionState.ACTIVE);
    final PlayerOwnershipService ownership = mock(PlayerOwnershipService.class);
    final PlayerStateService states = mock(PlayerStateService.class);
    final SyncModuleManager modules = mock(SyncModuleManager.class);
    final PlatformScheduler scheduler = mock(PlatformScheduler.class);
    final PlayerStateSnapshot snapshot = mock(PlayerStateSnapshot.class);
    final PlayerSessionCoordinator coordinator;

    LifecycleSafetyTest() {
        sessions.registerSession(session);
        sessions.markPlayerOnline(uuid);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(ownership.getServerId()).thenReturn("source");
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(snapshot);
        coordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null);
    }

    @Test
    void unresolvedConflictExplainsAdminReviewInsteadOfPromisingRetry() {
        when(states.hasFinalSaveConflict(uuid)).thenReturn(true);
        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");
        coordinator.handleAsyncPreLogin(event);
        var message = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(event).disallow(eq(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER), message.capture());
        assertThat(message.getValue().toString()).contains("administrator review");
        verify(ownership, never()).acquireOwnership(any(), any());
    }

    @Test
    void failedLoadQuitNeverCapturesOrCommitsPartialInventory() {
        session.markLoadFailed();
        session.forceState(SessionState.FAILED);
        coordinator.handlePlayerQuit(player);
        verify(modules, never()).captureSnapshot(any(), anyLong(), anyLong(), any(), any());
        verify(states, never()).commitDirect(any(), any());
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runAsync(task.capture());
        task.getValue().run();
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
    }

    @Test
    void preLoginCannotProceedWithoutDurableLoadingMarker() {
        when(ownership.acquireOwnership(eq(uuid), any())).thenReturn(
            new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(session.toIdentity(), 12));
        when(states.loadState(eq(uuid))).thenReturn(CompletableFuture.completedFuture(
            new dev.lyy.friendlysync.model.result.LoadResult.NotFound(12)));
        when(ownership.updateSessionState(session.toIdentity(), "LOADING")).thenReturn(false);
        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        coordinator.handleAsyncPreLogin(event);
        verify(event).disallow(eq(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
    }

    @Test
    void gracefulStopWaitsForRegionCaptureAndConfirmedCommit() throws Exception {
        CompletableFuture<CommitResult> commit = new CompletableFuture<>();
        when(states.commitDirect(any(), any())).thenReturn(commit);
        doAnswer(inv -> {
            sessions.removeSession(session.toIdentity());
            return true;
        }).when(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        CompletableFuture<Boolean> prepared;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            prepared = coordinator.prepareShutdown();
        }
        verify(states, never()).commitDirect(any(), any());
        ArgumentCaptor<Runnable> regionTask = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), regionTask.capture(), any());
        assertThat(prepared).isNotDone();
        regionTask.getValue().run();
        verify(modules).captureSnapshot(player, 12, 7, SyncReason.SHUTDOWN, session);
        assertThat(prepared).isNotDone();
        commit.complete(new CommitResult.Success(13, 1));
        assertThat(prepared.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void deathScreenShutdownStillRequiresConfirmedFinalSave(boolean succeeds) throws Exception {
        when(player.isDead()).thenReturn(true);
        var locks = new JoinLockService(sessions);
        var freeze = new dev.lyy.friendlysync.lock.TransferFreezeService(sessions, locks);
        var shutdown = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            locks, scheduler, null, null, freeze);
        var commit = new CompletableFuture<CommitResult>();
        when(states.commitDirect(snapshot, session.toIdentity())).thenReturn(commit);
        doAnswer(inv -> { sessions.removeSession(session.toIdentity()); return true; })
            .when(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        CompletableFuture<Boolean> prepared;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            prepared = shutdown.prepareShutdown();
        }
        verify(modules).captureSnapshot(player, 12, 7, SyncReason.SHUTDOWN, session);
        verify(states).commitDirect(snapshot, session.toIdentity());
        assertThat(locks.isLocked(uuid)).isTrue();
        assertThat(prepared).isNotDone();
        verify(ownership, never()).releaseOwnership(any(), anyString());
        commit.complete(succeeds ? new CommitResult.Success(13, 1)
            : new CommitResult.Failed(new IllegalStateException("SQL unavailable")));
        assertThat(prepared.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(succeeds);
        assertThat(locks.isLocked(uuid)).isTrue();
        if (succeeds) verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        else assertThat(sessions.getLocalSession(uuid)).contains(session);
        verify(player, never()).setHealth(anyDouble());
    }

    @Test
    void quitDuringGracefulStopDoesNotStartAnotherFinalSave() throws Exception {
        CompletableFuture<CommitResult> commit = new CompletableFuture<>();
        when(states.commitDirect(any(), any())).thenReturn(commit);
        doAnswer(inv -> { sessions.removeSession(session.toIdentity()); return true; })
            .when(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        CompletableFuture<Boolean> prepared;
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            prepared = coordinator.prepareShutdown();
            coordinator.handlePlayerQuit(player);
        }
        verify(states).commitDirect(snapshot, session.toIdentity());
        verify(modules).captureSnapshot(player, 12, 7, SyncReason.SHUTDOWN, session);
        verify(ownership, never()).releaseOwnership(any(), anyString());
        assertThat(prepared).isNotDone();
        commit.complete(new CommitResult.Success(13, 1));
        assertThat(prepared.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
    }

    @Test
    void shutdownReleaseExceptionCompletesPreparationWithoutWaitingForTimeout() throws Exception {
        when(states.commitDirect(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new CommitResult.Success(13, 1)));
        when(ownership.releaseOwnership(session.toIdentity(), "OFFLINE"))
            .thenThrow(new IllegalStateException("SQL disconnected during release"));
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            assertThat(coordinator.prepareShutdown().get(2, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
        assertThat(sessions.getLocalSession(uuid)).contains(session);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ACQUIRING,false", "LOADING,false", "FAILED,false",
        "ACQUIRING,true", "LOADING,true", "FAILED,true"})
    void gracefulStopReleasesUnloadedSessionsWithoutSavingPartialData(SessionState state, boolean online) throws Exception {
        session.forceState(state);
        if (state == SessionState.FAILED) session.markLoadFailed();
        doAnswer(inv -> { sessions.removeSession(session.toIdentity()); return true; })
            .when(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            // A pre-login connection may never have had a Bukkit Player or a quit event.
            if (online) bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            assertThat(coordinator.prepareShutdown().get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        verifyNoInteractions(modules);
        verify(states, never()).commitDirect(any(), any());
    }

    @Test
    void gracefulStopAbortsIfUnloadedSessionCannotBeReleased() throws Exception {
        session.forceState(SessionState.LOADING);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertThat(coordinator.prepareShutdown().get(2, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
        assertThat(sessions.getLocalSession(uuid)).contains(session);
        verifyNoInteractions(modules);
    }

    @Test
    void disableReleasesUnloadedSessionWithoutCapturingInventory() {
        session.forceState(SessionState.LOADING);
        when(ownership.releaseOwnership(session.toIdentity(), "OFFLINE")).thenReturn(true);
        coordinator.handleServerShutdown();
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        verifyNoInteractions(modules);
        verify(states, never()).commitDirect(any(), any());
    }

    @Test
    void gracefulStopFailureKeepsOwnershipAndAbortsStop() throws Exception {
        when(states.commitDirect(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new CommitResult.Failed(new IllegalStateException("database down"))));
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runGlobal(any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(any(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            assertThat(coordinator.prepareShutdown().get(2, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        }
        verify(ownership, never()).releaseOwnership(any(), anyString());
        assertThat(session.getState()).isEqualTo(SessionState.SAVING);
    }

    @Test
    void pendingRecoveryRejectsLoginBeforeAcquiringOwnership() {
        when(states.hasPendingFinalSave(uuid)).thenReturn(true);
        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        coordinator.handleAsyncPreLogin(event);
        verify(ownership, never()).acquireOwnership(any(), any());
        verify(event).disallow(eq(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void quitBeforeScheduledHandoffCapturesAndCommitsExactlyOnce() {
        CompletableFuture<CommitResult> commit = new CompletableFuture<>();
        when(states.commitDirect(any(), any())).thenReturn(commit);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination"));
            ArgumentCaptor<Runnable> capture = ArgumentCaptor.forClass(Runnable.class);
            ArgumentCaptor<Runnable> retired = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runForPlayer(eq(player), capture.capture(), retired.capture());
            when(player.isOnline()).thenReturn(false);
            coordinator.handlePlayerQuit(player);
            retired.getValue().run();
            capture.getValue().run(); // Even a stale delivery must not start another save.
        }
        verify(modules).captureSnapshot(player, 12, 7, SyncReason.QUIT, session);
        verify(states).commitDirect(snapshot, session.toIdentity());
        verify(ownership, never()).releaseOwnership(any(), anyString());
        commit.complete(new CommitResult.Success(13, 1));
        verify(ownership, timeout(2000)).releaseOwnership(session.toIdentity(), "OFFLINE");
    }

    @Test
    void shutdownCapturesOnCallingThreadAndWaitsOnlyForDatabaseWork() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> captureThread = new AtomicReference<>();
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenAnswer(inv -> {
            captureThread.set(Thread.currentThread());
            return snapshot;
        });
        when(states.commitDirect(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new CommitResult.Success(13, 1)));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            coordinator.handleServerShutdown();
        }
        assertThat(captureThread.get()).isSameAs(caller);
        verify(states).beginShutdown();
        verify(states, never()).saveState(any(), any(), anyBoolean());
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        verify(scheduler, never()).runGlobal(any());
        verify(scheduler, never()).runForPlayer(any(), any(), any());
    }

    @Test
    void failedShutdownCommitDoesNotReleaseLease() {
        when(states.commitDirect(any(), any())).thenReturn(
            CompletableFuture.completedFuture(new CommitResult.Failed(new IllegalStateException("SQL unavailable"))));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            coordinator.handleServerShutdown();
        }
        verify(ownership, never()).releaseOwnership(any(), anyString());
    }

    @Test
    void foliaShutdownNeverCapturesForeignPlayerRegion() {
        when(scheduler.isFolia()).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(player)).thenReturn(false);
            coordinator.handleServerShutdown();
        }
        verifyNoInteractions(modules);
        verify(states, never()).commitDirect(any(), any());
        verify(ownership, never()).releaseOwnership(any(), anyString());
    }

    @Test
    void shutdownDrainsAlreadyCapturedQuitSaveWithoutPluginScheduler() {
        CompletableFuture<CommitResult> commit = new CompletableFuture<>();
        when(states.commitDirect(any(), any())).thenReturn(commit);
        coordinator.handlePlayerQuit(player);
        // beginShutdown simulates a database completion once disable has started.
        doAnswer(inv -> { commit.complete(new CommitResult.Success(13, 1)); return null; })
            .when(states).beginShutdown();
        coordinator.handleServerShutdown();
        verify(states).commitDirect(snapshot, session.toIdentity());
        verify(ownership).releaseOwnership(session.toIdentity(), "OFFLINE");
        verify(scheduler, never()).runAsync(any());
    }

    @Test
    void offlineRequestDefersOwnershipQueryToWorker() {
        sessions.removeSession(uuid);
        sessions.markPlayerOffline(uuid);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination", "source"));
        }
        verify(ownership, never()).releaseOwnershipIfOwner(any(), anyString());
        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runAsync(worker.capture());
        worker.getValue().run();
        verify(ownership).releaseOwnershipIfOwner(uuid, "OFFLINE");
    }

    @Test
    void offlineRequestOnUninvolvedServerIsImmediatelyDiscarded() {
        sessions.removeSession(uuid);
        sessions.markPlayerOffline(uuid);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            // Message targeted at 'other-server', this server is 'source'
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination", "other-server"));
        }
        verify(scheduler, never()).runAsync(any());
        verify(ownership, never()).releaseOwnershipIfOwner(any(), anyString());
    }

    @Test
    void offlineRequestWithNullSourceOnUninvolvedServerIsImmediatelyDiscarded() {
        sessions.removeSession(uuid);
        sessions.markPlayerOffline(uuid);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            // No designated source, and player is offline with no local session
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination"));
        }
        verify(scheduler, never()).runAsync(any());
        verify(ownership, never()).releaseOwnershipIfOwner(any(), anyString());
    }

    @Test
    void offlineTransferRequestAwaitingQuitSavePublishesTransferReadyOnSuccess() {
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var testCoordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);

        CompletableFuture<CommitResult> saveFuture = new CompletableFuture<>();
        when(states.commitDirect(any(), any())).thenReturn(saveFuture);

        // Player quits
        testCoordinator.handlePlayerQuit(player);
        sessions.markPlayerOffline(uuid);

        // Incoming transfer request arrives while quit save is in flight
        ArgumentCaptor<Runnable> asyncTask = ArgumentCaptor.forClass(Runnable.class);
        testCoordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination", "source"));

        verify(scheduler).runAsync(asyncTask.capture());
        asyncTask.getValue().run(); // runs handleOfflineTransferRequest

        // Simulate save completing with Success
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runAsync(any());
        saveFuture.complete(new CommitResult.Success(8L, 10L));

        var readyCaptor = ArgumentCaptor.forClass(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.class);
        verify(messaging).publishTransferReady(readyCaptor.capture());
        assertThat(readyCaptor.getValue().playerUuid()).isEqualTo(uuid);
        assertThat(readyCaptor.getValue().revision()).isEqualTo(8L);
        assertThat(readyCaptor.getValue().targetServer()).isEqualTo("destination");
    }

    @Test
    void offlineTransferRequestWithMissingQuitSaveFutureAbortsAfterRetryLimit() {
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var testCoordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);

        // Put session directly into SAVING state without registering in activeQuitSaves
        session.forceState(SessionState.SAVING);
        sessions.markPlayerOffline(uuid);

        // Setup runAsyncLater to execute immediately
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runAsyncLater(any(), any());
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).runGlobal(any());
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).runAsync(any());

        // First 15 retries reschedule, 16th aborts
        for (int i = 0; i < 20; i++) {
            testCoordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination", "source"));
        }

        // Must never publish transfer ready since quit save never completed
        verify(messaging, never()).publishTransferReady(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preLoginWaitsForTransferReadySignalAndAcquiresOwnership() {
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var readyListenerCaptor = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        var testCoordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);
        verify(messaging).registerTransferListener(readyListenerCaptor.capture());
        var readyConsumer = (java.util.function.Consumer<dev.lyy.friendlysync.messaging.message.TransferReadyMessage>) readyListenerCaptor.getValue();

        when(ownership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofSeconds(5));
        when(ownership.acquireOwnership(uuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned("other", java.time.Instant.now().plusSeconds(60), 1L, "ACTIVE"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L));
        when(ownership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(1L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            readyConsumer.accept(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(uuid, 2, 1, "other", "source"));
        });

        testCoordinator.handleAsyncPreLogin(event);
        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
        verify(ownership, times(2)).acquireOwnership(uuid, "Player");
        verify(messaging).publishTransferRequest(any());
    }

    @Test
    void preLoginTimesOutWaitingForTransferReadyAndFallsBack() {
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var testCoordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);

        when(ownership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofMillis(50));
        when(ownership.acquireOwnership(uuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned("other", java.time.Instant.now().plusSeconds(60), 1L, "ACTIVE"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L));
        when(ownership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(1L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");

        testCoordinator.handleAsyncPreLogin(event);
        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
        verify(ownership, times(2)).acquireOwnership(uuid, "Player");
        verify(messaging).publishTransferRequest(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preLoginLocalOwnerUnblocksImmediatelyOnLocalSessionRelease() {
        var localOwnership = mock(PlayerOwnershipService.class);
        when(localOwnership.getServerId()).thenReturn("source");
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var releaseCaptor = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        var testCoordinator = new PlayerSessionCoordinator(localOwnership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);
        verify(localOwnership).setOnSessionReleased(releaseCaptor.capture());
        var releaseConsumer = (java.util.function.Consumer<UUID>) releaseCaptor.getValue();

        when(localOwnership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofSeconds(5));
        when(localOwnership.acquireOwnership(uuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned("source", java.time.Instant.now().plusSeconds(60), 1L, "ACTIVE"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L));
        when(localOwnership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(1L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");

        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {}
            releaseConsumer.accept(uuid);
        });

        long start = System.currentTimeMillis();
        testCoordinator.handleAsyncPreLogin(event);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2000L); // Unblocked rapidly on local release without waiting full 5s
        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
        verify(localOwnership, times(2)).acquireOwnership(uuid, "Player");
        // Local owner must NOT publish transfer request over Redis to itself
        verify(messaging, never()).publishTransferRequest(any());
    }

    @Test
    void preLoginLocalOwnerReleasesOnlyAnEligibleOrphanedOwnership() {
        var localSessions = new PlayerSessionService();
        var localOwnership = mock(PlayerOwnershipService.class);
        when(localOwnership.getServerId()).thenReturn("source");
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var testCoordinator = new PlayerSessionCoordinator(localOwnership, localSessions, states, modules,
            new JoinLockService(localSessions), scheduler, null, messaging, null);

        when(localOwnership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofSeconds(5));
        when(localOwnership.releaseOwnershipIfOwner(uuid, "OFFLINE")).thenReturn(true);
        when(localOwnership.acquireOwnership(uuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned("source", java.time.Instant.now().minusSeconds(1), 1L, "LOADING"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L));
        when(localOwnership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(1L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");

        testCoordinator.handleAsyncPreLogin(event);

        verify(localOwnership).releaseOwnershipIfOwner(uuid, "OFFLINE");
        verify(localOwnership, never()).forceReleaseOwnership(any(), any());
        verify(localOwnership, times(2)).acquireOwnership(uuid, "Player");
        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void offlineTransferRequestNeverForceReleasesAnActiveOrDifferentOwner() {
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        when(ownership.getServerId()).thenReturn("source");
        when(ownership.releaseOwnershipIfOwner(uuid, "OFFLINE")).thenReturn(false);

        var testCoordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            new JoinLockService(sessions), scheduler, null, messaging, null);

        sessions.removeSession(uuid);
        sessions.markPlayerOffline(uuid);

        ArgumentCaptor<Runnable> asyncTask = ArgumentCaptor.forClass(Runnable.class);
        testCoordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "destination", "source"));

        verify(scheduler).runAsync(asyncTask.capture());
        asyncTask.getValue().run();

        verify(ownership).releaseOwnershipIfOwner(uuid, "OFFLINE");
        verify(ownership, never()).forceReleaseOwnership(any(), any());
        verify(messaging, never()).publishTransferReady(any());
    }

    @Test
    void preLoginWithNullOwnerRetriesFencedAcquisitionWithoutBreakingOwnership() {
        UUID testUuid = UUID.randomUUID();
        when(ownership.acquireOwnership(testUuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned(null, null, 1L, "OFFLINE"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(testUuid, UUID.randomUUID(), "source", 1L), 1L));
        when(ownership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(testUuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(1L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(testUuid);
        when(event.getName()).thenReturn("Player");

        coordinator.handleAsyncPreLogin(event);

        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
        verify(ownership, times(2)).acquireOwnership(testUuid, "Player");
        verify(ownership, never()).breakExpiredLease(any(), any(), any());
    }

    @Test
    void joinWithoutSessionDefersKickByOneTick() {
        UUID unknownUuid = UUID.randomUUID();
        Player unknownPlayer = mock(Player.class);
        when(unknownPlayer.getUniqueId()).thenReturn(unknownUuid);
        when(unknownPlayer.getName()).thenReturn("Unknown");
        when(unknownPlayer.isOnline()).thenReturn(true);

        coordinator.handlePlayerJoin(unknownPlayer);

        // Immediate kick must not happen
        verify(unknownPlayer, never()).kick(any(net.kyori.adventure.text.Component.class));

        // Scheduler must be called with runForPlayerLater with 50ms
        var taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayerLater(eq(unknownPlayer), eq(java.time.Duration.ofMillis(50)), taskCaptor.capture());

        // When task executes, then player is kicked
        taskCaptor.getValue().run();
        verify(unknownPlayer, times(1)).kick(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void activeQuitSaveCompletionEnsuresOwnershipReleaseBeforeRejoinAcquisition() {
        var localSessions = new PlayerSessionService();
        var localOwnership = mock(PlayerOwnershipService.class);
        when(localOwnership.getServerId()).thenReturn("source");
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        var testCoordinator = new PlayerSessionCoordinator(localOwnership, localSessions, states, modules,
            new JoinLockService(localSessions), scheduler, null, messaging, null);

        var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Player");

        localSessions.registerSession(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1L), 1L, "Player", dev.lyy.friendlysync.model.SessionState.ACTIVE);
        var commitFuture = new CompletableFuture<dev.lyy.friendlysync.model.result.CommitResult>();
        when(states.commitDirect(any(), any())).thenReturn(commitFuture);
        when(localOwnership.releaseOwnership(any(), eq("OFFLINE"))).thenReturn(true);

        testCoordinator.handlePlayerQuit(player);

        when(localOwnership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofSeconds(5));
        when(localOwnership.acquireOwnership(uuid, "Player"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.AlreadyOwned("source", java.time.Instant.now().plusSeconds(60), 1L, "ACTIVE"))
            .thenReturn(new dev.lyy.friendlysync.model.result.OwnershipResult.Acquired(new SessionIdentity(uuid, UUID.randomUUID(), "source", 2L), 2L));
        when(localOwnership.updateSessionState(any(), any())).thenReturn(true);
        when(states.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new dev.lyy.friendlysync.model.result.LoadResult.NotFound(2L)));

        var event = mock(org.bukkit.event.player.AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("Player");

        CompletableFuture.runAsync(() -> {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            commitFuture.complete(new dev.lyy.friendlysync.model.result.CommitResult.Success(2L, 0));
        });

        testCoordinator.handleAsyncPreLogin(event);

        verify(localOwnership).releaseOwnership(any(), eq("OFFLINE"));
        verify(localOwnership, times(2)).acquireOwnership(uuid, "Player");
        verify(event, never()).disallow(any(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
    }
}
