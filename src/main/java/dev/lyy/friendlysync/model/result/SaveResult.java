package dev.lyy.friendlysync.model.result;

/**
 * Result of requesting a player state save operation.
 */
public sealed interface SaveResult {

    record Saved(
        long newRevision,
        long durationMs
    ) implements SaveResult {
    }

    record NotDirty(
        long currentRevision
    ) implements SaveResult {
    }

    record Conflict(
        String reason
    ) implements SaveResult {
    }

    record Failed(
        Throwable cause
    ) implements SaveResult {
    }
}
