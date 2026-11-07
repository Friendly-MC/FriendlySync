package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.model.SessionState;

import java.util.UUID;

/**
 * Read-only status view of a player's synchronization state.
 */
public record PlayerSyncStatus(
    UUID playerUuid,
    boolean isLocallyActive,
    SessionState sessionState,
    long revision,
    long fencingToken,
    boolean isDirty
) {
}
