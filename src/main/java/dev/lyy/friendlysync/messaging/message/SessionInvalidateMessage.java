package dev.lyy.friendlysync.messaging.message;

import java.util.UUID;

/**
 * Message published when an ownership lease is stolen or superseded, instructing the old owner to invalidate.
 */
public record SessionInvalidateMessage(
    int protocolVersion,
    String type,
    UUID operationId,
    UUID playerUuid,
    long newFencingToken,
    String claimingServer,
    long timestamp
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public static SessionInvalidateMessage create(UUID playerUuid, long newFencingToken, String claimingServer) {
        return new SessionInvalidateMessage(
            CURRENT_PROTOCOL_VERSION,
            "SESSION_INVALIDATE",
            UUID.randomUUID(),
            playerUuid,
            newFencingToken,
            claimingServer,
            System.currentTimeMillis()
        );
    }
}
