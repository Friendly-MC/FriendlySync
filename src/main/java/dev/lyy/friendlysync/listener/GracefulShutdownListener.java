package dev.lyy.friendlysync.listener;

import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.transfer.PlayerTransferService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures before Folia halts its region schedulers, rather than in onDisable. */
public final class GracefulShutdownListener implements Listener {
    private final PlayerSessionCoordinator coordinator;
    private final PlatformScheduler scheduler;
    private final FriendlySyncConfig config;
    private final PlayerTransferService transfers;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public GracefulShutdownListener(PlayerSessionCoordinator coordinator, PlatformScheduler scheduler,
                                    FriendlySyncConfig config, PlayerTransferService transfers) {
        this.coordinator = coordinator;
        this.scheduler = scheduler;
        this.config = config;
        this.transfers = transfers;
    }

    public static boolean isStopCommand(String command) {
        String name = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) name = name.substring(1);
        return name.equals("stop") || name.equals("minecraft:stop") || name.equals("bukkit:stop")
            || name.equals("paper:stop") || name.equals("spigot:stop")
            || name.equals("restart") || name.equals("paper:restart") || name.equals("spigot:restart");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleStop(ServerCommandEvent event) {
        if (isStopCommand(event.getCommand())) {
            event.setCancelled(true);
            stop(event.getSender(), isRestartCommand(event.getCommand()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRemoteStop(RemoteServerCommandEvent event) {
        onConsoleStop(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerStop(PlayerCommandPreprocessEvent event) {
        boolean restart = isRestartCommand(event.getMessage());
        if (isStopCommand(event.getMessage()) && event.getPlayer().hasPermission(
            restart ? "bukkit.command.restart" : "bukkit.command.stop")) {
            event.setCancelled(true);
            stop(event.getPlayer(), restart);
        }
    }

    private static boolean isRestartCommand(String command) {
        String name = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) name = name.substring(1);
        return name.equals("restart") || name.equals("paper:restart") || name.equals("spigot:restart");
    }

    public boolean isStopping() {
        return stopping.get();
    }

    public void stop(CommandSender sender, boolean restart) {
        if (!stopping.compareAndSet(false, true)) return;
        sender.sendMessage("FriendlySync: saving player data before stopping...");
        CompletableFuture<Boolean> preparation;
        try {
            preparation = coordinator.prepareShutdown();
        } catch (Exception e) {
            stopping.set(false);
            java.util.logging.Logger.getLogger("FriendlySync").log(java.util.logging.Level.SEVERE,
                "Shutdown preparation failed", e);
            sender.sendMessage("FriendlySync: stop aborted because shutdown preparation failed. Check the server log and retry stop.");
            return;
        }
        preparation.whenComplete((safe, error) -> scheduler.runGlobal(() -> {
            if (error == null && Boolean.TRUE.equals(safe)) {
                String target = config.getShutdownTransferTarget();
                if (target.isEmpty()) {
                    finishStop(restart);
                    return;
                }
                sender.sendMessage("FriendlySync: moving players to " + target + " before stopping...");
                evacuate(target).whenComplete((departed, transferError) -> scheduler.runGlobal(() -> {
                    if (transferError == null && Boolean.TRUE.equals(departed)) {
                        finishStop(restart);
                    } else if (config.isShutdownFallbackToDisconnect()) {
                        sender.sendMessage("FriendlySync: players could not leave for " + target
                            + "; disconnecting remaining players and proceeding with restart (data is saved)...");
                        for (var player : Bukkit.getOnlinePlayers()) {
                            scheduler.runForPlayer(player, () -> {
                                if (player.isOnline()) {
                                    player.kick(net.kyori.adventure.text.Component.text(
                                        "Server is restarting. Please reconnect shortly.",
                                        net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                                }
                            }, () -> { });
                        }
                        finishStop(restart);
                    } else {
                        stopping.set(false);
                        sender.sendMessage("FriendlySync: stop aborted because players could not leave for " + target
                            + ". Data is saved; remaining players stay locked. Check the target and proxy messaging, then retry stop.");
                    }
                }));
            } else {
                stopping.set(false);
                sender.sendMessage("FriendlySync: stop aborted because player data is not confirmed saved. Resolve storage errors, then retry stop. Players remain locked.");
            }
        }));
    }

    private CompletableFuture<Boolean> evacuate(String target) {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> departed = new CompletableFuture<>();
        try {
            // A connect request is not an acknowledgement. Keep the backend running until players leave.
            var watcher = scheduler.runGlobalRepeating(Duration.ofMillis(100), Duration.ofMillis(100), () -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) departed.complete(true);
            });
            var watched = departed.orTimeout(config.getTransferTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> watcher.cancel());
            for (var player : Bukkit.getOnlinePlayers()) {
                scheduler.runForPlayer(player, () -> {
                    if (departed.isDone() || !player.isOnline()) return;
                    try {
                        if (!transfers.sendToProxy(player, target)) {
                            departed.completeExceptionally(new IllegalStateException("Proxy dispatch failed"));
                        }
                    } catch (Exception e) {
                        departed.completeExceptionally(e);
                    }
                }, () -> { });
            }
            return watched;
        } catch (Exception e) {
            departed.completeExceptionally(e);
        }
        return departed;
    }

    private void finishStop(boolean restart) {
        try {
            if (restart && scheduler.isFolia()) {
                Bukkit.getLogger().info("FriendlySync: Folia does not support native restart; stopping after confirmed saves. Configure your process supervisor to start the backend again.");
                Bukkit.shutdown();
            } else if (restart) Bukkit.getServer().restart();
            else Bukkit.shutdown();
        } catch (RuntimeException e) {
            stopping.set(false);
            Bukkit.getLogger().log(java.util.logging.Level.SEVERE,
                "FriendlySync: native stop/restart failed; data is saved. Check restart support and retry stop.", e);
        }
    }
}
