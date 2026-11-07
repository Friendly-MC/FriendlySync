package dev.lyy.friendlysync.model.result;

/**
 * Result of an atomic revision commit attempt to durable storage.
 */
public sealed interface CommitResult {

    record Success(
        long newRevision,
        long durationMs
    ) implements CommitResult {
    }

    record Conflict(
        long expectedRevision,
        long expectedFencingToken,
        String reason
    ) implements CommitResult {
        public long revision() {
            return expectedRevision;
        }

        public long fencingToken() {
            return expectedFencingToken;
        }
    }

    record Failed(
        Throwable cause
    ) implements CommitResult {
    }
}
