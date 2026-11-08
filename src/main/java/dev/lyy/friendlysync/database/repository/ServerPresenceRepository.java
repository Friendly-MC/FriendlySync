package dev.lyy.friendlysync.database.repository;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.model.ServerInfo;
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
import java.util.UUID;

/**
 * Tracks backend server presence, startup instance IDs, and heartbeats.
 */
public final class ServerPresenceRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerPresenceRepository.class);

    private final DatabaseManager databaseManager;

    public ServerPresenceRepository(DatabaseManager databaseManager) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
    }

    public void registerServer(
        String serverId,
        UUID instanceId,
        String pluginVersion,
        String minecraftVersion,
        String platform
    ) {
        registerServer(serverId, instanceId, pluginVersion, minecraftVersion, platform, Duration.ofSeconds(45));
    }

    /**
     * Registers this process only when the server ID is not already represented by
     * another live instance. Duplicate server IDs break ownership semantics.
     */
    public boolean registerServer(
        String serverId,
        UUID instanceId,
        String pluginVersion,
        String minecraftVersion,
        String platform,
        Duration staleAfter
    ) {
        return registerServer(serverId, instanceId, pluginVersion, minecraftVersion, platform, staleAfter, false);
    }

    /**
     * Registers this process only when the server ID is not already represented by
     * another live instance, or reclaims presence if allowReclaim is true upon clean reboot.
     */
    public boolean registerServer(
        String serverId,
        UUID instanceId,
        String pluginVersion,
        String minecraftVersion,
        String platform,
        Duration staleAfter,
        boolean allowReclaim
    ) {
        try (Connection conn = databaseManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String checkSql = """
                    SELECT instance_id, last_heartbeat, CURRENT_TIMESTAMP AS database_now,
                           last_heartbeat >= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP) AS is_live
                    FROM friendlysync_server_presence
                    WHERE server_id = ?
                    FOR UPDATE
                    """;
                boolean rowExists = false;
                try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                    check.setQueryTimeout(25);
                    long staleSeconds = Math.max(1L, staleAfter.toSeconds());
                    check.setLong(1, -staleSeconds);
                    check.setString(2, serverId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            rowExists = true;
                            String existingInstance = rs.getString("instance_id");
                            boolean isLive = rs.getBoolean("is_live");
                            if (!instanceId.toString().equals(existingInstance)
                                && isLive) {
                                if (allowReclaim) {
                                    LOGGER.warn(
                                        "Server ID '{}' was previously active as instance {}; reclaiming presence for clean reboot",
                                        serverId,
                                        existingInstance
                                    );
                                } else {
                                    if (!conn.isClosed()) {
                                        try { conn.rollback(); } catch (SQLException ignored) {}
                                    }
                                    LOGGER.error(
                                        "Server ID '{}' is already active as instance {} (last heartbeat={}, database time={}, stale after={}s)",
                                        serverId,
                                        existingInstance,
                                        rs.getTimestamp("last_heartbeat"),
                                        rs.getTimestamp("database_now"),
                                        staleSeconds
                                    );
                                    return false;
                                }
                            }
                        }
                    }
                }

                if (rowExists) {
                    String updateSql = """
                        UPDATE friendlysync_server_presence
                        SET instance_id = ?, plugin_version = ?, minecraft_version = ?, platform = ?,
                            last_heartbeat = CURRENT_TIMESTAMP, started_at = CURRENT_TIMESTAMP
                        WHERE server_id = ?;
                        """;
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setQueryTimeout(25);
                        ps.setString(1, instanceId.toString());
                        ps.setString(2, pluginVersion);
                        ps.setString(3, minecraftVersion);
                        ps.setString(4, platform);
                        ps.setString(5, serverId);
                        ps.executeUpdate();
                    }
                } else {
                    String insertSql = """
                        INSERT INTO friendlysync_server_presence
                            (server_id, instance_id, plugin_version, minecraft_version, platform, last_heartbeat, started_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                        """;
                    try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                        insert.setQueryTimeout(25);
                        insert.setString(1, serverId);
                        insert.setString(2, instanceId.toString());
                        insert.setString(3, pluginVersion);
                        insert.setString(4, minecraftVersion);
                        insert.setString(5, platform);
                        insert.executeUpdate();
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                if (!conn.isClosed()) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
                throw e;
            } finally {
                if (!conn.isClosed()) {
                    try { conn.setAutoCommit(originalAutoCommit); } catch (SQLException ignored) {}
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to register server presence for {}: {}", serverId, e.getMessage(), e);
            return false;
        }
    }

    public void updateHeartbeat(String serverId, UUID instanceId) {
        String sql = """
            UPDATE friendlysync_server_presence
            SET last_heartbeat = CURRENT_TIMESTAMP
            WHERE server_id = ? AND instance_id = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(25);
            ps.setString(1, serverId);
            ps.setString(2, instanceId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.warn("Failed to update server presence heartbeat for {}: {}", serverId, e.getMessage(), e);
            } else {
                LOGGER.warn("Failed to update server presence heartbeat for {}: {}", serverId, e.getMessage());
            }
        }
    }

    /**
     * Removes this exact process from the presence table during a clean shutdown.
     * The instance predicate prevents a late shutdown from deleting a newer process.
     */
    public boolean unregisterServer(String serverId, UUID instanceId) {
        String sql = "DELETE FROM friendlysync_server_presence WHERE server_id = ? AND instance_id = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(25);
            ps.setString(1, serverId);
            ps.setString(2, instanceId.toString());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.warn("Failed to unregister server presence for {}: {}", serverId, e.getMessage(), e);
            return false;
        }
    }

    public List<ServerInfo> getActiveServers(Duration timeout) {
        List<ServerInfo> servers = new ArrayList<>();
        Instant cutoff = Instant.now().minus(timeout);
        String sql = """
            SELECT server_id, instance_id, plugin_version, minecraft_version, platform,
                   last_heartbeat, started_at
            FROM friendlysync_server_presence
            WHERE last_heartbeat >= ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    servers.add(new ServerInfo(
                        rs.getString("server_id"),
                        UUID.fromString(rs.getString("instance_id")),
                        rs.getString("plugin_version"),
                        rs.getString("minecraft_version"),
                        rs.getString("platform"),
                        rs.getTimestamp("last_heartbeat").toInstant(),
                        rs.getTimestamp("started_at").toInstant()
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to query active servers: {}", e.getMessage(), e);
        }
        return servers;
    }
}
