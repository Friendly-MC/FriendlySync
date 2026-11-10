package dev.lyy.friendlysync;

import dev.lyy.friendlysync.command.FriendlySyncCommand;
import dev.lyy.friendlysync.database.repository.*;
import dev.lyy.friendlysync.metrics.HealthService;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;

class RecoveryCommandTest {
    @Test void recoveryRequiresPermissionAndExplicitAcceptCurrentArgument() {
        var state = mock(PlayerStateService.class);
        when(state.reviewRecovery(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture("Reviewed"));
        var handler = new FriendlySyncCommand(new PlayerSessionService(), state, mock(HealthService.class),
            mock(ServerPresenceRepository.class), mock(PlatformScheduler.class), mock(PlayerStateRepository.class),
            mock(PlayerStateSerializer.class));
        var sender = mock(CommandSender.class);
        var command = mock(Command.class);
        UUID uuid = UUID.randomUUID();
        handler.onCommand(sender, command, "friendlysync", new String[]{"recovery", uuid.toString(), "accept-current"});
        verifyNoInteractions(state);
        when(sender.hasPermission("friendlysync.command.recovery")).thenReturn(true);
        handler.onCommand(sender, command, "friendlysync", new String[]{"recovery", "invalid"});
        handler.onCommand(sender, command, "friendlysync", new String[]{"recovery", uuid.toString(), "typo"});
        verifyNoInteractions(state);
        handler.onCommand(sender, command, "friendlysync", new String[]{"recovery", uuid.toString()});
        verify(state).reviewRecovery(uuid, false);
        handler.onCommand(sender, command, "friendlysync", new String[]{"recovery", uuid.toString(), "accept-current"});
        verify(state).reviewRecovery(uuid, true);

        // Bulk recovery commands
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(scheduler).runAsync(any());
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(scheduler).runGlobal(any());
        var bulkHandler = new FriendlySyncCommand(new PlayerSessionService(), state, mock(HealthService.class),
            mock(ServerPresenceRepository.class), scheduler, mock(PlayerStateRepository.class),
            mock(PlayerStateSerializer.class));

        when(state.getConflictPlayers()).thenReturn(java.util.List.of(uuid));
        when(state.bulkResolveConflicts(true)).thenReturn(CompletableFuture.completedFuture(
            new PlayerStateService.BulkRecoveryResult(1, 1, 0, java.util.List.of("Resolved"))
        ));

        bulkHandler.onCommand(sender, command, "friendlysync", new String[]{"bulkrecovery", "accept-current"});
        verify(state).bulkResolveConflicts(true);

        bulkHandler.onCommand(sender, command, "friendlysync", new String[]{"recoverall", "confirm"});
        verify(state, times(2)).bulkResolveConflicts(true);

        bulkHandler.onCommand(sender, command, "friendlysync", new String[]{"recovery", "all", "accept-current"});
        verify(state, times(3)).bulkResolveConflicts(true);
    }
}
