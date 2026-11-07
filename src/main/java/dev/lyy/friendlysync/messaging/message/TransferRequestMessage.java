package dev.lyy.friendlysync.messaging.message;

import java.util.UUID;

/**
 * Message published over Redis by a destination server when a player is still held by
 * another server during proxy server-switch (Velocity /rtp /server overlap).
 * Instructs the source server to flush the player's snapshot and release ownership immediately.
 */
public record TransferRequestMessage(
    int protocolVersion,
    String type,
    UUID operationId,
    UUID playerUuid,
    String destinationServer,
    long timestamp,
    String sourceServer
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public TransferRequestMessage(
        int protocolVersion,
        String type,
        UUID operationId,
        UUID playerUuid,
        String destinationServer,
        long timestamp
    ) {
        this(protocolVersion, type, operationId, playerUuid, destinationServer, timestamp, null);
    }

    public static TransferRequestMessage create(UUID playerUuid, String destinationServer) {
        return create(playerUuid, destinationServer, null);
    }

    public static TransferRequestMessage create(UUID playerUuid, String destinationServer, String sourceServer) {
        return new TransferRequestMessage(
            CURRENT_PROTOCOL_VERSION,
            "TRANSFER_REQUEST",
            UUID.randomUUID(),
            playerUuid,
            destinationServer,
            System.currentTimeMillis(),
            sourceServer
        );
    }
}
