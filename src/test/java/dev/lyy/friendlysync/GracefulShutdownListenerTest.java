package dev.lyy.friendlysync;

import dev.lyy.friendlysync.listener.GracefulShutdownListener;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GracefulShutdownListenerTest {
    @Test
    void synchronousPreparationFailureAllowsManualRetry() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        when(coordinator.prepareShutdown()).thenThrow(new IllegalStateException("Storage unavailable"));
        var listener = new GracefulShutdownListener(coordinator, mock(PlatformScheduler.class), config, transfers);
        var sender = mock(CommandSender.class);
        listener.stop(sender, true);
        assertThat(listener.isStopping()).isFalse();
        listener.stop(sender, true);
        verify(coordinator, times(2)).prepareShutdown();
    }

    final dev.lyy.friendlysync.config.FriendlySyncConfig config = mock(dev.lyy.friendlysync.config.FriendlySyncConfig.class);
    final dev.lyy.friendlysync.transfer.PlayerTransferService transfers = mock(dev.lyy.friendlysync.transfer.PlayerTransferService.class);

    @org.junit.jupiter.api.BeforeEach
    void configureShutdown() {
        when(config.getShutdownTransferTarget()).thenReturn("");
        when(config.getTransferTimeout()).thenReturn(java.time.Duration.ofSeconds(10));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void evacuationWaitsForSaveAndEveryDepartureBeforeStopOrRestart(boolean restart) {
        when(config.getShutdownTransferTarget()).thenReturn("spawn-a");
        var coordinator = mock(PlayerSessionCoordinator.class);
        var scheduler = mock(PlatformScheduler.class);
        var watcher = mock(dev.lyy.friendlysync.scheduler.ScheduledTaskHandle.class);
        when(scheduler.runGlobalRepeating(any(), any(), any())).thenReturn(watcher);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var prepared = new CompletableFuture<Boolean>();
        when(coordinator.prepareShutdown()).thenReturn(prepared);
        var first = mock(org.bukkit.entity.Player.class);
        var second = mock(org.bukkit.entity.Player.class);
        when(first.isOnline()).thenReturn(true);
        when(second.isOnline()).thenReturn(true);
        when(transfers.sendToProxy(any(), eq("spawn-a"))).thenReturn(true);
        var online = new java.util.ArrayList<>(java.util.List.of(first, second));
        var server = mock(org.bukkit.Server.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(inv -> online);
            var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
            listener.onConsoleStop(new ServerCommandEvent(mock(CommandSender.class), restart ? "restart" : "stop"));
            verifyNoInteractions(transfers);
            prepared.complete(true);
            // Dispatch must run on each player region, after SQL preparation completes.
            verifyNoInteractions(transfers);
            var packets = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler, times(2)).runForPlayer(any(), packets.capture(), any());
            packets.getAllValues().forEach(Runnable::run);
            verify(transfers).sendToProxy(first, "spawn-a");
            verify(transfers).sendToProxy(second, "spawn-a");
            var poll = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runGlobalRepeating(any(), any(), poll.capture());
            online.remove(first);
            poll.getValue().run();
            bukkit.verify(Bukkit::shutdown, never());
            verify(server, never()).restart();
            online.clear();
            poll.getValue().run();
            poll.getValue().run();
            if (restart) {
                verify(server).restart();
                bukkit.verify(Bukkit::shutdown, never());
            } else {
                bukkit.verify(Bukkit::shutdown);
                verify(server, never()).restart();
            }
            verify(watcher).cancel();
        }
    }

    @Test
    void failedPreparationNeverDispatchesEvacuation() {
        when(config.getShutdownTransferTarget()).thenReturn("spawn-a");
        var coordinator = mock(PlayerSessionCoordinator.class);
        when(coordinator.prepareShutdown()).thenReturn(CompletableFuture.completedFuture(false));
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            new GracefulShutdownListener(coordinator, scheduler, config, transfers)
                .onConsoleStop(new ServerCommandEvent(mock(CommandSender.class), "stop"));
            verifyNoInteractions(transfers);
            bukkit.verify(Bukkit::shutdown, never());
        }
    }

    @Test
    void evacuationTimeoutAbortsStopAndCancelsLatePackets() throws Exception {
        when(config.getShutdownTransferTarget()).thenReturn("spawn-a");
        when(config.getTransferTimeout()).thenReturn(java.time.Duration.ofMillis(50));
        var coordinator = mock(PlayerSessionCoordinator.class);
        when(coordinator.prepareShutdown()).thenReturn(CompletableFuture.completedFuture(true));
        var scheduler = mock(PlatformScheduler.class);
        var globals = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        doAnswer(inv -> { globals.add(inv.getArgument(0)); return null; }).when(scheduler).runGlobal(any());
        var watcher = mock(dev.lyy.friendlysync.scheduler.ScheduledTaskHandle.class);
        when(scheduler.runGlobalRepeating(any(), any(), any())).thenReturn(watcher);
        var player = mock(org.bukkit.entity.Player.class);
        when(player.isOnline()).thenReturn(true);
        var sender = mock(CommandSender.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(player));
            var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
            listener.onConsoleStop(new ServerCommandEvent(sender, "stop"));
            globals.take().run();
            var abort = globals.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(abort).isNotNull();
            abort.run();
            var latePacket = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runForPlayer(eq(player), latePacket.capture(), any());
            latePacket.getValue().run();
            verifyNoInteractions(transfers);
            verify(watcher).cancel();
            verify(sender).sendMessage(contains("stop aborted because players could not leave"));
            bukkit.verify(Bukkit::shutdown, never());
            listener.onConsoleStop(new ServerCommandEvent(sender, "stop"));
            verify(coordinator, times(2)).prepareShutdown();
        }
    }

    @Test
    void evacuationTimeoutFallsBackToDisconnectWhenConfigured() throws Exception {
        when(config.getShutdownTransferTarget()).thenReturn("spawn-a");
        when(config.getTransferTimeout()).thenReturn(java.time.Duration.ofMillis(50));
        when(config.isShutdownFallbackToDisconnect()).thenReturn(true);
        var coordinator = mock(PlayerSessionCoordinator.class);
        when(coordinator.prepareShutdown()).thenReturn(CompletableFuture.completedFuture(true));
        var scheduler = mock(PlatformScheduler.class);
        var globals = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        doAnswer(inv -> { globals.add(inv.getArgument(0)); return null; }).when(scheduler).runGlobal(any());
        var watcher = mock(dev.lyy.friendlysync.scheduler.ScheduledTaskHandle.class);
        when(scheduler.runGlobalRepeating(any(), any(), any())).thenReturn(watcher);
        var player = mock(org.bukkit.entity.Player.class);
        when(player.isOnline()).thenReturn(true);
        var sender = mock(CommandSender.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(player));
            var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
            listener.onConsoleStop(new ServerCommandEvent(sender, "stop"));
            globals.take().run();
            var fallback = globals.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(fallback).isNotNull();
            doAnswer(inv -> { inv.getArgument(1, Runnable.class).run(); return null; })
                .when(scheduler).runForPlayer(eq(player), any(), any());
            fallback.run();
            verify(player).kick(any(net.kyori.adventure.text.Component.class));
            bukkit.verify(Bukkit::shutdown);
        }
    }

    @Test
    void restartWaitsForConfirmedSaveThenUsesNativeRestart() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
        var prepared = new CompletableFuture<Boolean>();
        when(coordinator.prepareShutdown()).thenReturn(prepared);
        var server = mock(org.bukkit.Server.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            var event = new ServerCommandEvent(mock(CommandSender.class), "spigot:restart");
            listener.onConsoleStop(event);
            assertThat(event.isCancelled()).isTrue();
            verify(server, never()).restart();
            prepared.complete(true);
            verify(server).restart();
            bukkit.verify(Bukkit::shutdown, never());
        }
    }

    @Test
    void restartPermissionDoesNotAuthorizeStop() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        var player = mock(org.bukkit.entity.Player.class);
        when(player.hasPermission("bukkit.command.restart")).thenReturn(true);
        var listener = new GracefulShutdownListener(coordinator, mock(PlatformScheduler.class), config, transfers);
        var event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/stop", new java.util.HashSet<>());
        listener.onPlayerStop(event);
        assertThat(event.isCancelled()).isFalse();
        verifyNoInteractions(coordinator);
    }

    @Test
    void stopWaitsForPreparationAndDoesNotStopOnFailure() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
        var prepared = new CompletableFuture<Boolean>();
        when(coordinator.prepareShutdown()).thenReturn(prepared);
        try (var bukkit = mockStatic(Bukkit.class)) {
            var event = new ServerCommandEvent(mock(CommandSender.class), "minecraft:stop");
            listener.onConsoleStop(event);
            assertThat(event.isCancelled()).isTrue();
            bukkit.verify(Bukkit::shutdown, never());
            prepared.complete(false);
            bukkit.verify(Bukkit::shutdown, never());
        }
    }

    @Test
    void confirmedStopDispatchesShutdownExactlyOnce() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var listener = new GracefulShutdownListener(coordinator, scheduler, config, transfers);
        var prepared = new CompletableFuture<Boolean>();
        when(coordinator.prepareShutdown()).thenReturn(prepared);
        var sender = mock(CommandSender.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            listener.onConsoleStop(new ServerCommandEvent(sender, "stop"));
            listener.onConsoleStop(new ServerCommandEvent(sender, "stop"));
            verify(coordinator).prepareShutdown();
            bukkit.verify(Bukkit::shutdown, never());
            prepared.complete(true);
            bukkit.verify(Bukkit::shutdown);
        }
    }

    @Test
    void stopNamesAreExactAndSupportNamespacedCommands() {
        assertThat(GracefulShutdownListener.isStopCommand("/minecraft:stop")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("  STOP  ")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("stop message")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("/restart")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("/paper:restart")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("spigot:restart now")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("/paper:stop")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("spigot:stop")).isTrue();
        assertThat(GracefulShutdownListener.isStopCommand("stoplag")).isFalse();
        assertThat(GracefulShutdownListener.isStopCommand("say stop")).isFalse();
        assertThat(GracefulShutdownListener.isStopCommand("restartnow")).isFalse();
    }
    @Test
    void foliaRestartStopsOnlyAfterConfirmedSavesWithoutCallingDisabledNativeRestart() {
        var coordinator = mock(PlayerSessionCoordinator.class);
        var scheduler = mock(PlatformScheduler.class);
        when(scheduler.isFolia()).thenReturn(true);
        doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        var saved = new CompletableFuture<Boolean>();
        when(coordinator.prepareShutdown()).thenReturn(saved);
        var server = mock(org.bukkit.Server.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getLogger).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            new GracefulShutdownListener(coordinator, scheduler, config, transfers).stop(mock(CommandSender.class), true);
            bukkit.verify(Bukkit::shutdown, never());
            saved.complete(true);
            bukkit.verify(Bukkit::shutdown);
            verify(server, never()).restart();
        }
    }
}
