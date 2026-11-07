package dev.lyy.friendlysync.api;

/**
 * Audit reasons for triggering a player state synchronization commit.
 */
public enum SyncReason {
    INVENTORY_CHANGED,
    PERSISTENT_DATA_CHANGED,
    STATISTICS_CHANGED,
    LOCATION_CHANGED,
    EXTERNAL_PLUGIN,
    CHECKPOINT,
    TRANSFER,
    QUIT,
    SHUTDOWN,
    MANUAL,
    RECOVERY
}
