package dev.lyy.friendlysync.database.repository;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Repository for logging cross-server transfer lifecycle events for auditing and debugging.
 */
public final class TransferLogRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransferLogRepository.class);

    private final DatabaseManager databaseManager;

    public TransferLogRepository(DatabaseManager databaseManager) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
    }

    public void logTransferStart(
        UUID transferId,
        UUID playerUuid,
        UUID sessionId,
        String sourceServer,
        String targetServer,
        long sourceRevision
    ) {
        String sql = """
            INSERT INTO friendlysync_transfer_log
                (transfer_id, player_uuid, session_id, source_server, target_server, source_revision,
                 target_revision, status, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, NULL);
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, transferId.toString());
            ps.setString(2, playerUuid.toString());
            ps.setString(3, sessionId.toString());
            ps.setString(4, sourceServer);
            ps.setString(5, targetServer);
            ps.setLong(6, sourceRevision);
            ps.setLong(7, sourceRevision);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to log transfer start for {}: {}", transferId, e.getMessage(), e);
        }
    }

    public void logTransferComplete(UUID transferId, long targetRevision, String status) {
        String sql = """
            UPDATE friendlysync_transfer_log
            SET target_revision = ?,
                status = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE transfer_id = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setLong(1, targetRevision);
            ps.setString(2, status);
            ps.setString(3, transferId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to log transfer completion for {}: {}", transferId, e.getMessage(), e);
        }
    }

    public void cleanupOldLogs(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        String sql = "DELETE FROM friendlysync_transfer_log WHERE created_at < ? LIMIT 500;";

        int totalDeleted = 0;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setTimestamp(1, Timestamp.from(cutoff));
            int deleted;
            do {
                deleted = ps.executeUpdate();
                totalDeleted += deleted;
            } while (deleted == 500);
            if (totalDeleted > 0) {
                LOGGER.info("Cleaned up {} old transfer log entries older than {} days.", totalDeleted, retentionDays);
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to clean up old transfer logs: {}", e.getMessage(), e);
        }
    }
}
