package dev.lyy.friendlysync.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only view of persisted player state.
 */
public record PlayerStateView(
    UUID playerUuid,
    long revision,
    long fencingToken,
    String ownerServer,
    Instant updatedAt
) {
}
