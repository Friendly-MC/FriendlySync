package dev.lyy.friendlysync;

import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.messaging.message.TransferRequestMessage;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HandoffFailureTest {

    @Test
    void readyPublishFailureAfterCommitStillLocksSourceAndArmsProxyWatchdog() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("HandoffUser");
        when(player.isOnline()).thenReturn(true);
        PlayerSessionService sessions = new PlayerSessionService();
        ActivePlayerSession session = new ActivePlayerSession(uuid, UUID.randomUUID(), "spawn-a",
            7, 12, "HandoffUser", SessionState.ACTIVE);
        sessions.registerSession(session);
        sessions.markPlayerOnline(uuid);
        PlayerOwnershipService ownership = mock(PlayerOwnershipService.class);
        when(ownership.getServerId()).thenReturn("spawn-a");
        when(ownership.getAcquisitionWaitLimit()).thenReturn(java.time.Duration.ofSeconds(10));
        when(ownership.releaseOwnership(session.toIdentity(), "RELEASED")).thenAnswer(invocation -> {
            sessions.removeSession(session.toIdentity());
            return true;
        });
        PlayerStateService states = mock(PlayerStateService.class);
        when(states.commitDirect(any(), any())).thenReturn(CompletableFuture.completedFuture(new CommitResult.Success(13, 1)));
        SyncModuleManager modules = mock(SyncModuleManager.class);
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(mock(PlayerStateSnapshot.class));
        var messaging = mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        doThrow(new IllegalStateException("Redis unavailable")).when(messaging).publishTransferReady(any());
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runForPlayer(any(Player.class), any(Runnable.class), any(Runnable.class));
        var worker = mock(java.util.concurrent.ExecutorService.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(worker).execute(any());
        var locks = new JoinLockService(sessions);
        var coordinator = new PlayerSessionCoordinator(ownership, sessions, states, modules,
            locks, scheduler, null, messaging, null, worker);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "economy-a", "spawn-a"));
        }
        verify(ownership).releaseOwnership(session.toIdentity(), "RELEASED");
        assertThat(session.getState()).isEqualTo(SessionState.RELEASED);
        assertThat(locks.isLocked(uuid)).isTrue();
        verify(scheduler).runForPlayerLater(eq(player), eq(java.time.Duration.ofSeconds(10)), any());
        verify(player, never()).kick(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void failedCommitDoesNotReleaseOwnership() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("HandoffUser");
        when(player.isOnline()).thenReturn(true);

        PlayerSessionService sessions = new PlayerSessionService();
        ActivePlayerSession session = new ActivePlayerSession(
            playerId, UUID.randomUUID(), "server-A", 7, 12, "HandoffUser", SessionState.ACTIVE);
        sessions.registerSession(session);
        sessions.markPlayerOnline(playerId);

        PlayerOwnershipService ownership = mock(PlayerOwnershipService.class);
        when(ownership.getServerId()).thenReturn("server-A");
        PlayerStateService states = mock(PlayerStateService.class);
        when(states.commitDirect(any(), any())).thenReturn(CompletableFuture.completedFuture(
            new CommitResult.Failed(new IllegalStateException("database unavailable"))));
        SyncModuleManager modules = mock(SyncModuleManager.class);
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(mock(PlayerStateSnapshot.class));

        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runForPlayer(any(Player.class), any(Runnable.class), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runForPlayer(any(Player.class), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).runAsync(any(Runnable.class));

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownership, sessions, states, modules, new JoinLockService(sessions), scheduler, null);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            coordinator.handleIncomingTransferRequest(TransferRequestMessage.create(playerId, "server-B"));
        }

        verify(player, timeout(2000)).kick(any(net.kyori.adventure.text.Component.class));
        verify(ownership, never()).releaseOwnership(any(), anyString());
        assertThat(session.getState()).isEqualTo(SessionState.FAILED);
    }
}
