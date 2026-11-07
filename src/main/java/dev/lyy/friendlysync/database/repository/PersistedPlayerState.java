package dev.lyy.friendlysync.database.repository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Low-level domain entity mapping a row in the `friendlysync_players` table.
 */
public record PersistedPlayerState(
    UUID uuid,
    String username,
    long revision,
    long fencingToken,
    UUID activeSessionId,
    String ownerServer,
    String sessionState,
    Instant leaseExpiresAt,
    byte[] payload,
    int payloadVersion,
    String payloadHash,
    Instant updatedAt,
    Instant createdAt
) {
    public PersistedPlayerState {
        payload = payload != null ? payload.clone() : null;
    }

    @Override
    public byte[] payload() {
        return payload != null ? payload.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersistedPlayerState that)) return false;
        return revision == that.revision
            && fencingToken == that.fencingToken
            && payloadVersion == that.payloadVersion
            && Objects.equals(uuid, that.uuid)
            && Objects.equals(username, that.username)
            && Objects.equals(activeSessionId, that.activeSessionId)
            && Objects.equals(ownerServer, that.ownerServer)
            && Objects.equals(sessionState, that.sessionState)
            && Objects.equals(leaseExpiresAt, that.leaseExpiresAt)
            && Arrays.equals(payload, that.payload)
            && Objects.equals(payloadHash, that.payloadHash)
            && Objects.equals(updatedAt, that.updatedAt)
            && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(uuid, username, revision, fencingToken, activeSessionId, ownerServer, sessionState, leaseExpiresAt, payloadVersion, payloadHash, updatedAt, createdAt);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
