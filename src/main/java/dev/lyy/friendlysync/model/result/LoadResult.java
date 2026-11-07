package dev.lyy.friendlysync.model.result;

import dev.lyy.friendlysync.model.PlayerStateSnapshot;

/**
 * Result of loading persisted player state from durable storage.
 */
public sealed interface LoadResult {

    record Success(
        PlayerStateSnapshot snapshot
    ) implements LoadResult {
    }

    record NotFound(
        long initialRevision
    ) implements LoadResult {
    }

    record Corrupted(
        String reason,
        byte[] rawPayload
    ) implements LoadResult {
    }

    record Failed(
        Throwable cause
    ) implements LoadResult {
    }
}
