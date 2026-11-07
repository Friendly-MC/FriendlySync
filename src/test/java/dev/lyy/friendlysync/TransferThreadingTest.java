package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.TransferLogRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.transfer.PlayerTransferService;
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

class TransferThreadingTest {
    @Test
    void transferAuditWaitsForWorkerAndUsesRevisionAfterEarlierSave() {
        UUID uuid = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
            uuid, UUID.randomUUID(), "source", 7, 12, "Player", SessionState.ACTIVE);
        PlayerSessionService sessions = new PlayerSessionService();
        sessions.registerSession(session);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getHealth()).thenReturn(20.0);
        FriendlySyncConfig config = mock(FriendlySyncConfig.class);
        when(config.getServerId()).thenReturn("source");
        when(config.getTransferTimeout()).thenReturn(Duration.ofSeconds(10));
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        TransferLogRepository audit = mock(TransferLogRepository.class);
        ArrayDeque<Runnable> workers = new ArrayDeque<>();
        PlayerOperationQueue queue = new PlayerOperationQueue(workers::add);
        CompletableFuture<Void> earlierSave = new CompletableFuture<>();
        queue.submit(uuid, () -> earlierSave);
        workers.remove().run();
        PlayerTransferService transfers = new PlayerTransferService(
            mock(Plugin.class), config, scheduler, queue, sessions,
            mock(PlayerOwnershipService.class), mock(SyncModuleManager.class),
            mock(dev.lyy.friendlysync.session.PlayerStateService.class), audit,
            mock(MessagingService.class), mock(TransferFreezeService.class), new JoinLockService(sessions));

        var result = transfers.transfer(player, "target");

        // A stalled earlier save must not make the player thread perform JDBC work.
        verifyNoInteractions(audit);
        assertThat(result).isNotDone();
        session.setRevision(13);
        earlierSave.complete(null);
        workers.remove().run();

        verify(audit).logTransferStart(any(UUID.class), eq(uuid), eq(session.getSessionId()),
            eq("source"), eq("target"), eq(13L));
        verify(scheduler).runForPlayer(eq(player), any(Runnable.class), any(Runnable.class));
    }
}
