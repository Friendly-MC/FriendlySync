package dev.lyy.friendlysync.model;

import dev.lyy.friendlysync.util.Preconditions;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata representing a backend Minecraft server instance in the cluster.
 */
public record ServerInfo(
    String serverId,
    UUID instanceId,
    String pluginVersion,
    String minecraftVersion,
    String platform,
    Instant lastHeartbeat,
    Instant startedAt
) {
    public ServerInfo {
        Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");
        Preconditions.checkNotNull(instanceId, "instanceId cannot be null");
        Preconditions.checkNotEmpty(pluginVersion, "pluginVersion cannot be empty");
        Preconditions.checkNotEmpty(minecraftVersion, "minecraftVersion cannot be empty");
        Preconditions.checkNotEmpty(platform, "platform cannot be empty");
    }
}
