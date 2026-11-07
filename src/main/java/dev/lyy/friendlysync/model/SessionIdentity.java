package dev.lyy.friendlysync.model;

import dev.lyy.friendlysync.util.Preconditions;

import java.util.UUID;

/**
 * Immutable identity representing an authoritative ownership lease of a player.
 *
 * @param playerUuid   UUID of the player
 * @param sessionId    Unique UUID generated for this specific login session
 * @param serverId     Configured ID of the server holding ownership
 * @param fencingToken Monotonic token incremented on every ownership acquisition
 */
public record SessionIdentity(
    UUID playerUuid,
    UUID sessionId,
    String serverId,
    long fencingToken
) {
    public SessionIdentity {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        Preconditions.checkNotNull(sessionId, "sessionId cannot be null");
        Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");
        Preconditions.checkArgument(fencingToken >= 0, "fencingToken cannot be negative");
    }
}
