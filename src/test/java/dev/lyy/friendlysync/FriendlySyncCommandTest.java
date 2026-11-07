package dev.lyy.friendlysync;

import dev.lyy.friendlysync.command.FriendlySyncCommand;
import dev.lyy.friendlysync.database.repository.PersistedPlayerState;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRepository;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.ServerPresenceRepository;
import dev.lyy.friendlysync.metrics.HealthService;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.util.DigestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FriendlySyncCommandTest {
    private final PlayerSessionService sessions = new PlayerSessionService();
    private final PlayerStateService state = mock(PlayerStateService.class);
    private final HealthService health = mock(HealthService.class);
    private final PlatformScheduler scheduler = mock(PlatformScheduler.class);
    private final PlayerStateRepository repository = mock(PlayerStateRepository.class);
    private final PlayerStateSerializer serializer = mock(PlayerStateSerializer.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final List<String> messages = new ArrayList<>();
    private final FriendlySyncCommand handler = new FriendlySyncCommand(sessions, state, health,
        mock(ServerPresenceRepository.class), scheduler, repository, serializer);

    FriendlySyncCommandTest() {
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(scheduler).runGlobal(any());
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(scheduler).runAsync(any());
        doAnswer(call -> { messages.add(((TextComponent) call.<Component>getArgument(0)).content()); return null; })
            .when(sender).sendMessage(any(Component.class));
    }

    @Test void delegatedPermissionFiltersHelpAndCompletionAndWorksInTurkishLocale() {
        when(sender.hasPermission("friendlysync.command.player")).thenReturn(true);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(handler.canUse(sender)).isTrue();
            assertThat(handler.onTabComplete(sender, null, "fsync", new String[]{""}))
                .containsExactly("player", "snapshots", "help");
            handler.onCommand(sender, null, "fsync", new String[]{"help"});
            assertThat(messages).anyMatch(s -> s.contains("snapshots"));
            assertThat(messages).noneMatch(s -> s.contains("restore") || s.contains("unlock"));
            handler.onCommand(sender, null, "fsync", new String[]{"STATUS"});
            verifyNoInteractions(health, state, repository);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test void deniedCommandsAndInvalidArityNeverRunAnOperation() {
        assertThat(handler.canUse(sender)).isFalse();
        assertThat(handler.onTabComplete(sender, null, "fsync", new String[]{""})).isEmpty();
        handler.onCommand(sender, null, "fsync", new String[]{"unlock", UUID.randomUUID().toString()});
        when(sender.hasPermission("friendlysync.admin")).thenReturn(true);
        for (String sub : List.of("status", "health", "servers", "reload")) {
            handler.onCommand(sender, null, "fsync", new String[]{sub, "unexpected"});
        }
        for (String sub : List.of("player", "save", "unlock", "snapshots", "restore", "recovery")) {
            handler.onCommand(sender, null, "fsync", new String[]{sub});
            handler.onCommand(sender, null, "fsync", new String[]{sub, "x", "y", "z"});
        }
        handler.onCommand(sender, null, "fsync", new String[]{"typo"});
        verifyNoInteractions(health, state, repository);
        verify(scheduler, never()).runAsync(any());
        assertThat(messages).anyMatch(s -> s.contains("Usage: /fsync status"));
        assertThat(messages).anyMatch(s -> s.contains("Unknown subcommand"));
    }

    @Test void recoveryAndRestoreRejectShortIdsAndCompleteTheirOptionalArguments() {
        when(sender.hasPermission("friendlysync.admin")).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        handler.onCommand(sender, null, "fsync", new String[]{"recovery", "1-1-1-1-1"});
        handler.onCommand(sender, null, "fsync", new String[]{"recovery", uuid.toString(), "typo"});
        handler.onCommand(sender, null, "fsync", new String[]{"restore", uuid.toString(), "12345678"});
        verifyNoInteractions(state, repository);
        assertThat(handler.onTabComplete(sender, null, "fsync", new String[]{"recovery", uuid.toString(), "ac"}))
            .containsExactly("accept-current");
        assertThat(handler.onTabComplete(sender, null, "fsync", new String[]{"restore", uuid.toString(), "la"}))
            .containsExactly("latest");
    }

    @Test void unlockRefusesPendingConflictsAndLocalSessions() throws Exception {
        when(sender.hasPermission("friendlysync.admin")).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        String[] args = {"unlock", uuid.toString()};
        when(state.hasPendingFinalSave(uuid)).thenReturn(true);
        handler.onCommand(sender, null, "fsync", args);
        when(state.hasPendingFinalSave(uuid)).thenReturn(false);
        when(state.hasFinalSaveConflict(uuid)).thenReturn(true);
        handler.onCommand(sender, null, "fsync", args);
        when(state.hasFinalSaveConflict(uuid)).thenReturn(false);
        sessions.markPlayerOnline(uuid);
        handler.onCommand(sender, null, "fsync", args);
        verifyNoInteractions(repository);
        sessions.markPlayerOffline(uuid);
        when(repository.find(uuid)).thenReturn(Optional.empty());
        handler.onCommand(sender, null, "fsync", args);
        verify(repository).unlockExpiredOwnership(uuid);
        verify(repository, never()).forceReleaseOwnership(any(), any());
    }

    @Test void snapshotListingShowsTheFullIdAndPlayerAuditAcceptsAbsentModules() throws Exception {
        when(sender.hasPermission("friendlysync.admin")).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        var snapshots = mock(PlayerSnapshotRepository.class);
        var record = mock(PlayerSnapshotRecord.class);
        when(record.snapshotId()).thenReturn(id);
        when(repository.getSnapshotRepository()).thenReturn(snapshots);
        when(snapshots.findRecentSnapshots(uuid, 5)).thenReturn(List.of(record));
        handler.onCommand(sender, null, "fsync", new String[]{"snapshots", uuid.toString()});
        assertThat(messages).anyMatch(s -> s.contains(id));
        byte[] payload = {1, 2};
        when(repository.find(uuid)).thenReturn(Optional.of(new PersistedPlayerState(uuid, "Player", 0, 0,
            null, null, "OFFLINE", null, payload, 3, DigestUtils.sha256(payload), Instant.now(), Instant.now())));
        var snapshot = mock(PlayerStateSnapshot.class);
        when(snapshot.persistentData()).thenReturn(PersistentDataSnapshot.EMPTY);
        when(snapshot.statistics()).thenReturn(StatisticsSnapshot.EMPTY);
        when(serializer.deserialize(payload)).thenReturn(snapshot);
        handler.onCommand(sender, null, "fsync", new String[]{"player", uuid.toString()});
        assertThat(messages).contains("Inventory: absent", "Ender Chest: absent");
        assertThat(messages).noneMatch(s -> s.contains("Deserialization Error"));
    }

    @Test void asynchronousRepliesUseThePlayersSchedulerAndWorkerFailuresGiveFeedback() {
        var player = mock(Player.class);
        when(player.hasPermission("friendlysync.command.recovery")).thenReturn(true);
        UUID uuid = UUID.randomUUID();
        var future = new CompletableFuture<String>();
        when(state.reviewRecovery(uuid, false)).thenReturn(future);
        handler.onCommand(player, null, "fsync", new String[]{"recovery", uuid.toString()});
        future.complete("Reviewed");
        verify(scheduler).runForPlayer(eq(player), any());
        verify(player, never()).sendMessage(any(Component.class));
        when(sender.hasPermission("friendlysync.command.health")).thenReturn(true);
        when(health.generateReport()).thenThrow(new IllegalStateException("Database unavailable"));
        handler.onCommand(sender, null, "fsync", new String[]{"health"});
        assertThat(messages).contains("Command failed: Database unavailable");
    }
}
