package dev.lyy.friendlysync.database.repository;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.util.DigestUtils;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for durable player snapshot history stored in MySQL (`friendlysync_snapshots`).
 * Retains historical snapshots; it cannot recover changes that were never captured.
 */
public final class PlayerSnapshotRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSnapshotRepository.class);
    static final int PRUNE_BATCH_SIZE = 500;

    private final DatabaseManager databaseManager;

    public PlayerSnapshotRepository(DatabaseManager databaseManager) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
    }

    public void insertSnapshot(Connection conn, PlayerSnapshotRecord record) throws SQLException {
        Preconditions.checkNotNull(conn, "conn cannot be null");
        Preconditions.checkNotNull(record, "record cannot be null");

        String sql = """
            INSERT INTO friendlysync_snapshots
                (snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                 sync_reason, status, payload, payload_version, payload_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = VALUES(status), payload_hash = VALUES(payload_hash);
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, record.snapshotId());
            ps.setString(2, record.playerUuid().toString());
            ps.setLong(3, record.revision());
            ps.setLong(4, record.fencingToken());
            ps.setString(5, record.sessionId() != null ? record.sessionId().toString() : "");
            ps.setString(6, record.serverId() != null ? record.serverId() : "unknown");
            ps.setString(7, record.syncReason() != null ? record.syncReason() : "SYNC");
            ps.setString(8, record.status() != null ? record.status() : "COMMITTED");
            byte[] payload = record.payload();
            ps.setBytes(9, payload);
            ps.setInt(10, record.payloadVersion());
            ps.setString(11, record.payloadHash());
            ps.setTimestamp(12, Timestamp.from(record.createdAt() != null ? record.createdAt() : Instant.now()));

            if (payload != null) databaseManager.recordPayloadUpload(payload.length);
            ps.executeUpdate();
        }
    }

    public void insertSnapshot(PlayerSnapshotRecord record) throws SQLException {
        Preconditions.checkNotNull(record, "record cannot be null");
        try (Connection conn = databaseManager.getConnection()) {
            insertSnapshot(conn, record);
        }
    }

    /**
     * Copies exactly the confirmed revision without retransmitting its payload. If a
     * concurrent writer changed the row, return false so the caller preserves its
     * original captured bytes through the normal insert path.
     */
    public boolean copyCommittedSnapshot(Connection conn, PlayerSnapshotRecord record) throws SQLException {
        String sql = """
            INSERT INTO friendlysync_snapshots
                (snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                 sync_reason, status, payload, payload_version, payload_hash, created_at)
            SELECT ?, uuid, revision, fencing_token, active_session_id, ?, ?, 'COMMITTED',
                   payload, payload_version, payload_hash, ?
            FROM friendlysync_players
            WHERE uuid = ? AND revision = ? AND fencing_token = ?
              AND active_session_id = ? AND payload_hash = ? AND payload_version = ?;
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, record.snapshotId());
            ps.setString(2, record.serverId() != null ? record.serverId() : "unknown");
            ps.setString(3, record.syncReason() != null ? record.syncReason() : "SYNC");
            ps.setTimestamp(4, Timestamp.from(record.createdAt() != null ? record.createdAt() : Instant.now()));
            ps.setString(5, record.playerUuid().toString());
            ps.setLong(6, record.revision());
            ps.setLong(7, record.fencingToken());
            ps.setString(8, record.sessionId().toString());
            ps.setString(9, record.payloadHash());
            ps.setInt(10, record.payloadVersion());
            return ps.executeUpdate() == 1;
        }
    }

    public Optional<PlayerSnapshotRecord> findLatestSnapshot(UUID playerUuid) throws SQLException {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        // Fast path: use composite index (player_uuid, status, revision, created_at) for COMMITTED snapshots
        String committedSql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ? AND status = 'COMMITTED'
            ORDER BY revision DESC, created_at DESC
            LIMIT 1;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(committedSql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }

        // Fallback for non-committed snapshots if no committed record exists
        String fallbackSql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ?
            ORDER BY revision DESC, created_at DESC
            LIMIT 1;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(fallbackSql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** Recovery may only substitute bytes identical to the authoritative SQL commit. */
    public Optional<PlayerSnapshotRecord> findExactCommittedSnapshot(UUID uuid, long revision, String hash) throws SQLException {
        if (hash == null || hash.isBlank()) return Optional.empty();
        String sql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ? AND revision = ? AND payload_hash = ? AND status = 'COMMITTED'
            ORDER BY created_at DESC LIMIT 1;
            """;
        try (Connection conn = databaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            ps.setLong(2, revision);
            ps.setString(3, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                PlayerSnapshotRecord record = mapResultSet(rs);
                byte[] payload = record.payload();
                return payload != null && hash.equalsIgnoreCase(DigestUtils.sha256(payload))
                    ? Optional.of(record) : Optional.empty();
            }
        }
    }

    /**
     * Finds the newest uncorrupted valid snapshot for a player by verifying SHA-256 payload integrity.
     * Prioritizes COMMITTED snapshots over SUPERSEDED snapshots, then searches in descending revision and creation order.
     */
    public Optional<PlayerSnapshotRecord> findLatestValidSnapshot(UUID playerUuid) throws SQLException {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        String sql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ?
            ORDER BY (CASE WHEN status = 'COMMITTED' THEN 0 ELSE 1 END) ASC, revision DESC, created_at DESC
            LIMIT 10;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerSnapshotRecord record = mapResultSet(rs);
                    if (record.payload() != null && record.payloadHash() != null) {
                        String hash = DigestUtils.sha256(record.payload());
                        if (hash.equalsIgnoreCase(record.payloadHash())) {
                            return Optional.of(record);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Fast index-only query to find the maximum persisted snapshot revision for a player.
     * Avoids reading or transferring LONGBLOB payloads over JDBC.
     */
    public Optional<Long> getHighestRevision(UUID playerUuid) throws SQLException {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        String sql = """
            SELECT revision
            FROM friendlysync_snapshots
            WHERE player_uuid = ?
            ORDER BY revision DESC
            LIMIT 1;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("revision"));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<PlayerSnapshotRecord> findHighestRevisionSnapshot(UUID playerUuid) throws SQLException {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        String sql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ?
            ORDER BY revision DESC, created_at DESC
            LIMIT 10;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerSnapshotRecord record = mapResultSet(rs);
                    if (record.payload() != null && record.payloadHash() != null) {
                        String hash = DigestUtils.sha256(record.payload());
                        if (hash.equalsIgnoreCase(record.payloadHash())) {
                            return Optional.of(record);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public Optional<PlayerSnapshotRecord> findSnapshotById(String snapshotId) throws SQLException {
        Preconditions.checkNotNull(snapshotId, "snapshotId cannot be null");

        String sql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE snapshot_id = ?
            LIMIT 1;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerSnapshotRecord record = mapResultSet(rs);
                    if (record.payload() != null && record.payloadHash() != null) {
                        String hash = DigestUtils.sha256(record.payload());
                        if (hash.equalsIgnoreCase(record.payloadHash())) {
                            return Optional.of(record);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    public List<PlayerSnapshotRecord> findRecentSnapshots(UUID playerUuid, int limit) throws SQLException {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        String sql = """
            SELECT snapshot_id, player_uuid, revision, fencing_token, session_id, server_id,
                   sync_reason, status, payload, payload_version, payload_hash, created_at
            FROM friendlysync_snapshots
            WHERE player_uuid = ?
            ORDER BY revision DESC, (CASE WHEN status = 'COMMITTED' THEN 0 ELSE 1 END), created_at DESC
            LIMIT ?;
            """;

        List<PlayerSnapshotRecord> list = new ArrayList<>();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, playerUuid.toString());
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSet(rs));
                }
            }
        }
        return list;
    }

    public boolean updateSnapshotStatus(String snapshotId, String status) throws SQLException {
        Preconditions.checkNotEmpty(snapshotId, "snapshotId cannot be empty");
        Preconditions.checkNotEmpty(status, "status cannot be empty");

        String sql = "UPDATE friendlysync_snapshots SET status = ? WHERE snapshot_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, status);
            ps.setString(2, snapshotId);
            return ps.executeUpdate() > 0;
        }
    }

    public int pruneOldSnapshots(Duration retentionDuration) throws SQLException {
        if (retentionDuration == null || retentionDuration.isZero() || retentionDuration.isNegative()) {
            return 0;
        }

        Instant threshold = Instant.now().minus(retentionDuration);
        // Chunked so a large backlog of blob rows cannot exceed the timeout and roll back every time.
        String sql = "DELETE FROM friendlysync_snapshots WHERE created_at < ? LIMIT " + PRUNE_BATCH_SIZE;
        int total = 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setTimestamp(1, Timestamp.from(threshold));
            int deleted;
            do {
                deleted = ps.executeUpdate();
                total += deleted;
            } while (deleted == PRUNE_BATCH_SIZE);
        }
        return total;
    }

    private PlayerSnapshotRecord mapResultSet(ResultSet rs) throws SQLException {
        String sessionIdStr = rs.getString("session_id");
        UUID sessionId = (sessionIdStr != null && !sessionIdStr.isBlank()) ? UUID.fromString(sessionIdStr) : null;
        Timestamp ts = rs.getTimestamp("created_at");

        byte[] payload = rs.getBytes("payload");
        if (payload != null) databaseManager.recordPayloadDownload(payload.length);
        return new PlayerSnapshotRecord(
            rs.getString("snapshot_id"),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getLong("revision"),
            rs.getLong("fencing_token"),
            sessionId,
            rs.getString("server_id"),
            rs.getString("sync_reason"),
            rs.getString("status"),
            payload,
            rs.getInt("payload_version"),
            rs.getString("payload_hash"),
            ts != null ? ts.toInstant() : Instant.now()
        );
    }
}
