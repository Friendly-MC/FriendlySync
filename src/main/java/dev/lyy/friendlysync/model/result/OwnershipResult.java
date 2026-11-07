package dev.lyy.friendlysync.model.result;

import dev.lyy.friendlysync.model.SessionIdentity;

import java.time.Instant;

/**
 * Result of an atomic ownership acquisition attempt.
 */
public sealed interface OwnershipResult {

    record Acquired(
        SessionIdentity session,
        long currentRevision
    ) implements OwnershipResult {
    }

    record AlreadyOwned(
        String ownerServer,
        Instant leaseExpiresAt,
        long currentFencingToken,
        String sessionState
    ) implements OwnershipResult {
    }

    record Conflict(
        String reason
    ) implements OwnershipResult {
    }

    record DatabaseUnavailable(
        Throwable cause
    ) implements OwnershipResult {
    }
}
