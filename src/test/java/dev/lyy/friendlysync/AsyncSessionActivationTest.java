package dev.lyy.friendlysync;

import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AsyncSessionActivationTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("Player stays locked until asynchronous durable activation succeeds")
    void testActivationDispatchesStateUpdateAsync(boolean confirmed) {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        // Synchronously run whatever is scheduled on runForPlayer so we simulate the player thread execution
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1, Runnable.class);
            task.run();
            return null;
        }).when(scheduler).runForPlayer(any(Player.class), any(Runnable.class));

        // Capture whatever is dispatched to runAsync
        ArgumentCaptor<Runnable> asyncCaptor = ArgumentCaptor.forClass(Runnable.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(playerUuid, sessionId, "test-server", 5L);

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "test-server", 5L, 1L, "TestPlayer",
            SessionState.LOADING, Instant.now()
        );
        sessionService.registerSession(session);

        Player player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.isOnline()).thenReturn(true);
        when(ownershipService.updateSessionState(identity, "ACTIVE")).thenReturn(confirmed);

        // Execute player join (triggers activation)
        coordinator.handlePlayerJoin(player);

        assertThat(session.getState()).isEqualTo(SessionState.LOADING);

        assertThat(joinLockService.isLocked(playerUuid)).isTrue();

        // 3. updateSessionState must NOT have been called directly on the player thread
        verify(ownershipService, never()).updateSessionState(any(), any());

        // 4. A task was submitted to runAsync
        verify(scheduler).runAsync(asyncCaptor.capture());
        Runnable asyncTask = asyncCaptor.getValue();
        assertThat(asyncTask).isNotNull();

        // When the async task runs on the worker thread, it updates the DB
        asyncTask.run();
        verify(ownershipService, times(1)).updateSessionState(eq(identity), eq("ACTIVE"));
        assertThat(session.getState()).isEqualTo(confirmed ? SessionState.ACTIVE : SessionState.FAILED);
        assertThat(joinLockService.isLocked(playerUuid)).isEqualTo(!confirmed);
        if (!confirmed) {
            verify(player).kick(any(net.kyori.adventure.text.Component.class));
            assertThat(session.hasFailedLoad()).isTrue();
        }
    }

    @Test
    @DisplayName("handleLoginDisallowed cleans session in-memory and releases DB ownership asynchronously")
    void testLoginDisallowedReleasesOwnershipAsync() {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        ArgumentCaptor<Runnable> asyncCaptor = ArgumentCaptor.forClass(Runnable.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(playerUuid, sessionId, "test-server", 10L);

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "test-server", 10L, 1L, "BlockedPlayer",
            SessionState.ACQUIRING, Instant.now()
        );
        sessionService.registerSession(session);

        // Trigger disallowed login (e.g. from PlayerLoginEvent on main thread)
        coordinator.handleLoginDisallowed(playerUuid);

        // 1. In-memory session is removed immediately
        assertThat(sessionService.getLocalSession(playerUuid)).isEmpty();

        // 2. releaseOwnership must NOT have been called synchronously
        verify(ownershipService, never()).releaseOwnership(any(), any());

        // 3. Database release must be delegated to runAsync
        verify(scheduler).runAsync(asyncCaptor.capture());
        asyncCaptor.getValue().run();
        verify(ownershipService, times(1)).releaseOwnership(eq(identity), eq("OFFLINE"));
    }

    @Test
    @DisplayName("handlePlayerQuit in TRANSFER_PENDING releases DB ownership asynchronously")
    void testPlayerQuitInTransferPendingReleasesAsync() {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        ArgumentCaptor<Runnable> asyncCaptor = ArgumentCaptor.forClass(Runnable.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionIdentity identity = new SessionIdentity(playerUuid, sessionId, "test-server", 20L);

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "test-server", 20L, 1L, "TransferringPlayer",
            SessionState.TRANSFER_PENDING, Instant.now()
        );
        sessionService.registerSession(session);

        Player player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("TransferringPlayer");

        coordinator.handlePlayerQuit(player);

        // In-memory session removed immediately
        assertThat(sessionService.getLocalSession(playerUuid)).isEmpty();

        // Synchronous DB release did NOT happen
        verify(ownershipService, never()).releaseOwnership(any(), any());

        // Offloaded to async scheduler
        verify(scheduler).runAsync(asyncCaptor.capture());
        asyncCaptor.getValue().run();
        verify(ownershipService, times(1)).releaseOwnership(eq(identity), eq("OFFLINE"));
    }

    @Test
    @DisplayName("handlePlayerQuit in RELEASED cleans up in-memory session without re-releasing DB ownership")
    void testPlayerQuitInReleasedDoesNotReReleaseOwnership() {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "test-server", 20L, 1L, "ReleasedPlayer",
            SessionState.RELEASED, Instant.now()
        );
        sessionService.registerSession(session);

        Player player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("ReleasedPlayer");

        coordinator.handlePlayerQuit(player);

        // In-memory session removed immediately
        assertThat(sessionService.getLocalSession(playerUuid)).isEmpty();

        // Must NOT release ownership again since it was already released to RELEASED
        verify(ownershipService, never()).releaseOwnership(any(), any());
        verify(stateService, never()).commitDirect(any(), any());
    }

    @Test
    @DisplayName("handlePlayerQuit during FREEZING transfer state does not launch duplicate commit")
    void testPlayerQuitInFreezingDoesNotLaunchDuplicateCommit() {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "test-server", 20L, 1L, "FreezingPlayer",
            SessionState.FREEZING, Instant.now()
        );
        sessionService.registerSession(session);

        Player player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("FreezingPlayer");

        coordinator.handlePlayerQuit(player);

        // Does NOT launch a duplicate commit
        verify(stateService, never()).commitDirect(any(), any());
        verify(stateService, never()).saveState(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Incoming TransferRequest during active quit save awaits commit and does not release ownership prematurely")
    void testIncomingTransferRequestDuringQuitSaveAwaitsCompletion() {
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        when(ownershipService.getServerId()).thenReturn("server-a");

        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);
        dev.lyy.friendlysync.messaging.MessagingService messagingService = Mockito.mock(dev.lyy.friendlysync.messaging.MessagingService.class);

        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(scheduler).runAsync(any(Runnable.class));

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null, messagingService, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "server-a", 10L, 5L, "QuittingPlayer",
            SessionState.ACTIVE, Instant.now()
        );
        sessionService.registerSession(session);

        Player player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("QuittingPlayer");

        dev.lyy.friendlysync.model.PlayerStateSnapshot snapshot = Mockito.mock(dev.lyy.friendlysync.model.PlayerStateSnapshot.class);
        when(syncModuleManager.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(snapshot);

        java.util.concurrent.CompletableFuture<CommitResult> saveFuture = new java.util.concurrent.CompletableFuture<>();
        when(stateService.commitDirect(any(), any())).thenReturn(saveFuture);

        // 1. Player quits -> launches saveFuture (uncompleted)
        coordinator.handlePlayerQuit(player);
        assertThat(session.getState()).isEqualTo(SessionState.SAVING);

        // 2. TransferRequest arrives while saveFuture is still executing
        dev.lyy.friendlysync.messaging.message.TransferRequestMessage request =
            dev.lyy.friendlysync.messaging.message.TransferRequestMessage.create(playerUuid, "economy-a");
        coordinator.handleIncomingTransferRequest(request);

        // Crucial assertion: releaseOwnership must NOT have been called while save is running!
        verify(ownershipService, never()).releaseOwnership(any(), any());
        verify(messagingService, never()).publishTransferReady(any());

        // 3. Complete the quit save
        saveFuture.complete(new CommitResult.Success(11L, 12L));

        // Now ownership is released as OFFLINE by quit handler, and TransferReady is published
        verify(ownershipService, timeout(2000).times(1)).releaseOwnership(eq(session.toIdentity()), eq("OFFLINE"));
        ArgumentCaptor<dev.lyy.friendlysync.messaging.message.TransferReadyMessage> readyCaptor =
            ArgumentCaptor.forClass(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.class);
        verify(messagingService, times(1)).publishTransferReady(readyCaptor.capture());
        assertThat(readyCaptor.getValue().playerUuid()).isEqualTo(playerUuid);
        assertThat(readyCaptor.getValue().targetServer()).isEqualTo("economy-a");
        assertThat(readyCaptor.getValue().revision()).isEqualTo(11L);
    }
}
