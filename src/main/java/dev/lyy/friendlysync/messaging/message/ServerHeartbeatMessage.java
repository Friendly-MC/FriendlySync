package dev.lyy.friendlysync.messaging.message;

import java.util.UUID;

/**
 * Message published periodically to announce server presence and heartbeat.
 */
public record ServerHeartbeatMessage(
    int protocolVersion,
    String type,
    String serverId,
    UUID instanceId,
    long timestamp
) {
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public static ServerHeartbeatMessage create(String serverId, UUID instanceId) {
        return new ServerHeartbeatMessage(
            CURRENT_PROTOCOL_VERSION,
            "SERVER_HEARTBEAT",
            serverId,
            instanceId,
            System.currentTimeMillis()
        );
    }
}
