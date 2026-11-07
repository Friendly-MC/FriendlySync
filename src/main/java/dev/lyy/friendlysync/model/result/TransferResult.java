package dev.lyy.friendlysync.model.result;

/**
 * Result of initiating a cross-server transfer for a player.
 */
public sealed interface TransferResult {

    record Success(
        String targetServer,
        long committedRevision
    ) implements TransferResult {
    }

    record Failed(
        String reason,
        Throwable cause
    ) implements TransferResult {
    }

    record TimedOut(
        String targetServer
    ) implements TransferResult {
    }
}
