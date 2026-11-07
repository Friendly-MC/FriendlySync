package dev.lyy.friendlysync.messaging.message;

import java.util.UUID;

/**
 * Message published over Redis when a source server finishes the final snapshot commit and is ready for transfer.
 */
public record TransferReadyMessage(
    int protocolVersion,
    String type,
    UUID operationId,
    UUID playerUuid,
    long revision,
    long fencingToken,
    String sourceServer,
    String targetServer,
    long timestamp
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public static TransferReadyMessage create(
        UUID playerUuid,
        long revision,
        long fencingToken,
        String sourceServer,
        String targetServer
    ) {
        return new TransferReadyMessage(
            CURRENT_PROTOCOL_VERSION,
            "TRANSFER_READY",
            UUID.randomUUID(),
            playerUuid,
            revision,
            fencingToken,
            sourceServer,
            targetServer,
            System.currentTimeMillis()
        );
    }
}
