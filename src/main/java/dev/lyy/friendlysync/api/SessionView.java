package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.model.SessionState;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable read-only view of an active session.
 */
public record SessionView(
    UUID playerUuid,
    UUID sessionId,
    String serverId,
    long fencingToken,
    long revision,
    SessionState state,
    Instant lastHeartbeat
) {
}
