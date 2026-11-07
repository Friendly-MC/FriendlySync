package dev.lyy.friendlysync.scheduler;

import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Cross-platform scheduler abstraction supporting both Paper and Folia.
 * Dispatches work to the correct context (entity, global region, or async thread pool).
 */
public interface PlatformScheduler {

    /**
     * Executes a task immediately on the global server thread / global region.
     */
    void runGlobal(Runnable task);

    /**
     * Executes a task asynchronously on an off-server worker pool.
     */
    void runAsync(Runnable task);

    /**
     * Executes a task on the thread owning the specified player.
     * Guaranteed safe for player and inventory manipulation on Folia and Paper.
     */
    void runForPlayer(Player player, Runnable task);

    /**
     * Schedules work for a player and invokes {@code retired} if the entity can no
     * longer accept tasks. Callers that complete futures should use this overload
     * so an offline player cannot leave an operation permanently pending.
     */
    void runForPlayer(Player player, Runnable task, Runnable retired);

    /**
     * Executes a task on the player's owning thread after the specified delay.
     */
    void runForPlayerLater(Player player, Duration delay, Runnable task);

    /**
     * Schedules a one-shot asynchronous task after the specified delay.
     */
    ScheduledTaskHandle runAsyncLater(Duration delay, Runnable task);

    /**
     * Schedules a repeating asynchronous task.
     */
    ScheduledTaskHandle runAsyncRepeating(Duration delay, Duration period, Runnable task);

    /**
     * Schedules a repeating task on the global region.
     */
    ScheduledTaskHandle runGlobalRepeating(Duration delay, Duration period, Runnable task);

    /**
     * Returns true if the server is running Folia.
     */
    boolean isFolia();
}
