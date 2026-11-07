package dev.lyy.friendlysync.database.repository;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Repository for logging session history and heartbeats in `friendlysync_sessions`.
 */
public final class SessionRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRepository.class);

    private final DatabaseManager databaseManager;

    public SessionRepository(DatabaseManager databaseManager) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
    }

    public void recordSessionStart(
        UUID sessionId,
        UUID playerUuid,
        String serverId,
        long fencingToken,
        String state
    ) {
        String sql = """
            INSERT INTO friendlysync_sessions
                (session_id, player_uuid, server_id, fencing_token, state, started_at, heartbeat_at, ended_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
            ON DUPLICATE KEY UPDATE heartbeat_at = CURRENT_TIMESTAMP, state = VALUES(state);
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, sessionId.toString());
            ps.setString(2, playerUuid.toString());
            ps.setString(3, serverId);
            ps.setLong(4, fencingToken);
            ps.setString(5, state);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to record session start for {}: {}", sessionId, e.getMessage(), e);
        }
    }

    public void recordHeartbeats(java.util.Collection<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }

        String sql = """
            UPDATE friendlysync_sessions
            SET heartbeat_at = CURRENT_TIMESTAMP
            WHERE session_id = ?
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            for (UUID sessionId : sessionIds) {
                ps.setString(1, sessionId.toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.warn("Failed to record batched session heartbeats: {}", e.getMessage(), e);
        }
    }


    public void recordSessionEnd(UUID sessionId, String finalState) {
        String sql = """
            UPDATE friendlysync_sessions
            SET state = ?,
                ended_at = CURRENT_TIMESTAMP
            WHERE session_id = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, finalState);
            ps.setString(2, sessionId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to record session end for {}: {}", sessionId, e.getMessage(), e);
        }
    }
}
