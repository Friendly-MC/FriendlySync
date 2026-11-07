package dev.lyy.friendlysync.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler implementation for Paper platform.
 * Delegates to modern Paper unified schedulers.
 */
public class PaperScheduler implements PlatformScheduler {

    private final Plugin plugin;
    private final boolean folia;

    public PaperScheduler(Plugin plugin) {
        this(plugin, false);
    }

    protected PaperScheduler(Plugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    @Override
    public void runGlobal(Runnable task) {
        if (!folia && Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    @Override
    public void runForPlayer(Player player, Runnable task) {
        runForPlayer(player, task, () -> {});
    }

    @Override
    public void runForPlayer(Player player, Runnable task, Runnable retired) {
        if (!player.isOnline()) {
            retired.run();
            return;
        }
        if (ownsPlayer(player)) {
            task.run();
            return;
        }
        player.getScheduler().run(plugin, scheduledTask -> task.run(), retired);
    }

    @Override
    public void runForPlayerLater(Player player, Duration delay, Runnable task) {
        if (!player.isOnline()) {
            return;
        }
        long delayTicks = Math.max(1, delay.toMillis() / 50);
        player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
    }

    @Override
    public ScheduledTaskHandle runAsyncLater(Duration delay, Runnable task) {
        ScheduledTask scheduledTask = Bukkit.getAsyncScheduler().runDelayed(
            plugin,
            st -> task.run(),
            Math.max(1, delay.toMillis()),
            TimeUnit.MILLISECONDS
        );
        return wrapTask(scheduledTask);
    }

    @Override
    public ScheduledTaskHandle runAsyncRepeating(Duration delay, Duration period, Runnable task) {
        ScheduledTask scheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            st -> task.run(),
            Math.max(1, delay.toMillis()),
            Math.max(1, period.toMillis()),
            TimeUnit.MILLISECONDS
        );
        return wrapTask(scheduledTask);
    }

    @Override
    public ScheduledTaskHandle runGlobalRepeating(Duration delay, Duration period, Runnable task) {
        long delayTicks = Math.max(1, delay.toMillis() / 50);
        long periodTicks = Math.max(1, period.toMillis() / 50);
        ScheduledTask scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            st -> task.run(),
            delayTicks,
            periodTicks
        );
        return wrapTask(scheduledTask);
    }

    private boolean ownsPlayer(Player player) {
        if (!folia) return Bukkit.isPrimaryThread();
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean isFolia() {
        return folia;
    }

    private ScheduledTaskHandle wrapTask(ScheduledTask task) {
        return new ScheduledTaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }
}
