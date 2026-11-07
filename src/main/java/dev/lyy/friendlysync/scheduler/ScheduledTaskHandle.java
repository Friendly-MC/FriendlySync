package dev.lyy.friendlysync.scheduler;

/**
 * Handle for controlling and cancelling a scheduled task across Paper and Folia.
 */
@FunctionalInterface
public interface ScheduledTaskHandle {

    void cancel();

    default boolean isCancelled() {
        return false;
    }
}
