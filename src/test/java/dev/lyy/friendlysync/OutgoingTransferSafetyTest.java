package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.TransferLogRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.model.result.*;
import dev.lyy.friendlysync.scheduler.*;
import dev.lyy.friendlysync.session.*;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.transfer.PlayerTransferService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutgoingTransferSafetyTest {
    @Test
    void rtpReturnMarkerAndTaggedWatchdogAreArmedOnlyAfterCommitAndOwnershipRelease() throws Exception {
        UUID request = UUID.randomUUID();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var result = transfers.transferRtp(player, "overworld", request);
            verify(player, never()).sendPluginMessage(any(), anyString(), any());
            workers.remove().run();
            assertThat(result.join()).isInstanceOf(TransferResult.Success.class);
            var ordered = inOrder(states, ownership, player);
            ordered.verify(states).commitFinalSnapshotInQueue(snapshot, session.toIdentity());
            ordered.verify(ownership).releaseOwnership(session.toIdentity(), "RELEASED");
            var packet = org.mockito.ArgumentCaptor.forClass(byte[].class);
            ordered.verify(player).sendPluginMessage(any(), eq("friendlycore:rtp_return"), packet.capture());
            ordered.verify(player).sendPluginMessage(any(), eq("BungeeCord"), any());
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet.getValue()))) {
                assertThat(input.readUnsignedByte()).isEqualTo(1);
                assertThat(new UUID(input.readLong(), input.readLong())).isEqualTo(request);
                assertThat(input.readUTF()).isEqualTo("overworld");
                assertThat(input.available()).isZero();
            }
            var watchdog = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runForPlayerLater(eq(player), any(), watchdog.capture());
            watchdog.getValue().run();
            var reason = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
            verify(player).kick(reason.capture());
            assertThat(reason.getValue().toString()).contains("friendlycore:rtp-return:" + request);
            assertThat(locks.isLocked(uuid)).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void failedRtpSaveOrReleaseCannotArmProxyReturn(boolean saveFails) {
        if (saveFails) when(states.commitFinalSnapshotInQueue(snapshot, session.toIdentity()))
                .thenReturn(new CommitResult.Failed(new IllegalStateException("SQL unavailable")));
        else doReturn(false).when(ownership).releaseOwnership(session.toIdentity(), "RELEASED");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var result = transfers.transferRtp(player, "overworld", UUID.randomUUID());
            workers.remove().run();
            assertThat(result.join()).isInstanceOf(TransferResult.Failed.class);
            verify(player, never()).sendPluginMessage(any(), anyString(), any());
        }
    }
    final UUID uuid = UUID.randomUUID();
    final Player player = mock(Player.class);
    final ActivePlayerSession session = new ActivePlayerSession(uuid, UUID.randomUUID(), "economy", 7, 12, "Player", SessionState.ACTIVE);
    final PlayerSessionService sessions = new PlayerSessionService();
    final JoinLockService locks = new JoinLockService(sessions);
    final PlayerStateService states = mock(PlayerStateService.class);
    final PlayerOwnershipService ownership = mock(PlayerOwnershipService.class);
    final SyncModuleManager modules = mock(SyncModuleManager.class);
    final PlatformScheduler scheduler = mock(PlatformScheduler.class);
    final ArrayDeque<Runnable> workers = new ArrayDeque<>();
    final PlayerOperationQueue queue = new PlayerOperationQueue(Runnable::run);
    final PlayerStateSnapshot snapshot = mock(PlayerStateSnapshot.class);
    final Runnable[] timeout = new Runnable[1];
    final PlayerTransferService transfers;

    OutgoingTransferSafetyTest() {
        sessions.registerSession(session);
        sessions.markPlayerOnline(uuid);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.getHealth()).thenReturn(20.0);
        var config = mock(FriendlySyncConfig.class);
        when(config.getServerId()).thenReturn("economy");
        when(config.getTransferTimeout()).thenReturn(Duration.ofSeconds(10));
        when(scheduler.runAsyncLater(any(), any())).thenAnswer(inv -> {
            timeout[0] = inv.getArgument(1); return mock(ScheduledTaskHandle.class);
        });
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(eq(player), any(), any());
        doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(eq(player), any());
        doAnswer(inv -> { workers.add(inv.getArgument(0)); return null; }).when(scheduler).runAsync(any());
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var freeze = mock(TransferFreezeService.class);
        when(freeze.freeze(player)).thenAnswer(inv -> { locks.setExplicitlyLocked(uuid, true); return session.transitionTo(SessionState.FREEZING); });
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(snapshot);
        when(states.commitFinalSnapshotInQueue(snapshot, session.toIdentity())).thenReturn(new CommitResult.Success(13, 1));
        when(ownership.releaseOwnership(session.toIdentity(), "RELEASED")).thenAnswer(inv -> {
            sessions.removeSession(session.toIdentity()); locks.remove(uuid); return true;
        });
        transfers = new PlayerTransferService(mock(Plugin.class), config, scheduler, queue, sessions, ownership, modules,
            states, mock(TransferLogRepository.class), mock(MessagingService.class), freeze, locks);
    }

    @Test
    void timeoutDoesNotReleaseQueueOrCancelCapturedSaveAndNeverSendsLateConnect() {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var result = transfers.transfer(player, "afk");
            var durable = transfers.getInFlightTransfer(uuid);
            var next = queue.submit(uuid, () -> CompletableFuture.completedFuture("next"));
            timeout[0].run();
            assertThat(result.join()).isInstanceOf(TransferResult.TimedOut.class);
            assertThat(durable).isNotDone();
            assertThat(next).isNotDone();
            assertThat(session.getState()).isEqualTo(SessionState.FREEZING);
            workers.remove().run();
            verify(states).commitFinalSnapshotInQueue(snapshot, session.toIdentity());
            verify(ownership).releaseOwnership(session.toIdentity(), "RELEASED");
            verify(player, never()).sendPluginMessage(any(), anyString(), any());
            assertThat(next.join()).isEqualTo("next");
            assertThat(transfers.getInFlightTransfer(uuid)).isNull();
        }
    }

    @Test
    void quitAndShutdownAwaitExistingOutgoingCaptureInsteadOfSavingItAgain() throws Exception {
        var coordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules, locks, scheduler, null);
        coordinator.configureOutgoingTransfers(transfers);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            var result = transfers.transfer(player, "afk");
            coordinator.handlePlayerQuit(player);
            var shutdown = coordinator.prepareShutdown();
            assertThat(shutdown).isNotDone();
            verify(states, never()).commitDirect(any(), any());
            workers.remove().run();
            assertThat(shutdown.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(modules, times(1)).captureSnapshot(any(), anyLong(), anyLong(), any(), any());
            assertThat(result.join()).isInstanceOf(TransferResult.Success.class);
        }
    }

    @Test
    void workerRejectionRetainsCaptureForRecoveryAndCompletesTransfer() {
        doThrow(new java.util.concurrent.RejectedExecutionException("full")).when(scheduler).runAsync(any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            assertThat(transfers.transfer(player, "afk").join()).isInstanceOf(TransferResult.Failed.class);
            verify(states).retainRejectedFinalSave(snapshot, session.toIdentity());
            verify(ownership, never()).releaseOwnership(any(), anyString());
            assertThat(session.getState()).isEqualTo(SessionState.FAILED);
        }
    }
    @Test
    void failedTransferQuitKeepsRecoveryCaptureWithoutAnotherCommit() {
        var coordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules, locks, scheduler, null);
        session.forceState(SessionState.FAILED);
        when(states.hasPendingFinalSave(uuid)).thenReturn(true);
        coordinator.handlePlayerQuit(player);
        verify(modules, never()).captureSnapshot(any(), anyLong(), anyLong(), any(), any());
        verify(states, never()).commitDirect(any(), any());
        verify(ownership, never()).releaseOwnership(any(), anyString());
        assertThat(sessions.getLocalSession(uuid)).isEmpty();
    }    @Test
    void disconnectSchedulingFailureCannotLeaveFailedSaveQueuePending() {
        when(states.commitFinalSnapshotInQueue(snapshot, session.toIdentity()))
            .thenReturn(new CommitResult.Failed(new IllegalStateException("SQL failed")));
        doThrow(new IllegalStateException("scheduler unavailable")).when(scheduler).runForPlayer(eq(player), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var result = transfers.transfer(player, "afk");
            workers.remove().run();
            assertThat(result).isDone();
            assertThat(result.join()).isInstanceOf(TransferResult.Failed.class);
            assertThat(transfers.getInFlightTransfer(uuid)).isNull();
            assertThat(queue.getActiveQueueCount()).isZero();
        }
    }

    @Test
    void timeoutSchedulingFailureDoesNotLeaveAFalseDuplicateTransfer() {
        when(scheduler.runAsyncLater(any(), any())).thenThrow(new IllegalStateException("scheduler unavailable"));
        assertThat(transfers.transfer(player, "afk")).isCompletedExceptionally();
        assertThat(transfers.getInFlightTransfer(uuid)).isNull();
        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);
    }    @Test
    void shutdownAlsoWaitsForProxyDispatchAfterSqlSessionHasBeenReleased() throws Exception {
        var dispatches = new ArrayDeque<Runnable>();
        doAnswer(inv -> {
            var task = inv.getArgument(1, Runnable.class);
            if (session.getState() == SessionState.RELEASED) dispatches.add(task);
            else task.run();
            return null;
        }).when(scheduler).runForPlayer(eq(player), any(), any());
        var coordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules, locks, scheduler, null);
        coordinator.configureOutgoingTransfers(transfers);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
            var result = transfers.transfer(player, "afk");
            workers.remove().run();
            assertThat(sessions.getLocalSession(uuid)).isEmpty();
            assertThat(result).isNotDone();
            var shutdown = coordinator.prepareShutdown();
            assertThat(shutdown).isNotDone();
            dispatches.remove().run();
            assertThat(shutdown.get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(states, never()).commitDirect(any(), any());
        }
    }}
