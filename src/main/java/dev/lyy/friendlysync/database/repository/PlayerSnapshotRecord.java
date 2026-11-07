package dev.lyy.friendlysync.database.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a persistent player state snapshot record stored in MySQL (`friendlysync_snapshots`).
 */
public record PlayerSnapshotRecord(
    String snapshotId,
    UUID playerUuid,
    long revision,
    long fencingToken,
    UUID sessionId,
    String serverId,
    String syncReason,
    String status,
    byte[] payload,
    int payloadVersion,
    String payloadHash,
    Instant createdAt
) {
    public PlayerSnapshotRecord {
        if (payload != null) {
            payload = payload.clone();
        }
    }

    @Override
    public byte[] payload() {
        return payload != null ? payload.clone() : null;
    }
}
