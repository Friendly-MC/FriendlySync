package dev.lyy.friendlysync.database.repository;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * High-performance repository for atomic player state persistence, lease renewal, and fencing token enforcement.
 */
public final class PlayerStateRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStateRepository.class);

    private final DatabaseManager databaseManager;
    private final PlayerSnapshotRepository snapshotRepository;

    public PlayerStateRepository(DatabaseManager databaseManager) {
        this(databaseManager, new PlayerSnapshotRepository(databaseManager));
    }

    public PlayerStateRepository(DatabaseManager databaseManager, PlayerSnapshotRepository snapshotRepository) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
        this.snapshotRepository = snapshotRepository;
    }

    public PlayerSnapshotRepository getSnapshotRepository() {
        return snapshotRepository;
    }

    private volatile boolean continuousSnapshotsEnabled = false;

    public void setContinuousSnapshotsEnabled(boolean continuousSnapshotsEnabled) {
        this.continuousSnapshotsEnabled = continuousSnapshotsEnabled;
    }

    public boolean isContinuousSnapshotsEnabled() {
        return continuousSnapshotsEnabled;
    }

    public boolean isSnapshotEligible(String syncReason) {
        if (continuousSnapshotsEnabled) {
            return true;
        }
        if (syncReason == null) {
            return false;
        }
        String normalized = syncReason.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("QUIT")
            || normalized.contains("TRANSFER")
            || normalized.contains("SHUTDOWN")
            || normalized.equals("DIRECT");
    }

    /**
     * Finds the persisted record for a player by UUID.
     */
    public Optional<PersistedPlayerState> find(UUID uuid) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        String sql = """
            SELECT uuid, username, revision, fencing_token, active_session_id, owner_server,
                   session_state, lease_expires_at, payload, payload_version, payload_hash,
                   updated_at, created_at
            FROM friendlysync_players
            WHERE uuid = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** Reads commit identity without transferring the LONGBLOB over the database connection. */
    public Optional<PlayerStateMetadata> findMetadata(UUID uuid) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        String sql = """
            SELECT revision, fencing_token, active_session_id, owner_server, payload_hash
            FROM friendlysync_players WHERE uuid = ?;
            """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String session = rs.getString("active_session_id");
                return Optional.of(new PlayerStateMetadata(rs.getLong("revision"), rs.getLong("fencing_token"),
                    session != null ? UUID.fromString(session) : null,
                    rs.getString("owner_server"), rs.getString("payload_hash")));
            }
        }
    }

    /**
     * Finds the persisted record for a player by username.
     */
    public Optional<PersistedPlayerState> findByUsername(String username) throws SQLException {
        Preconditions.checkNotEmpty(username, "username cannot be empty");
        String sql = """
            SELECT uuid, username, revision, fencing_token, active_session_id, owner_server,
                   session_state, lease_expires_at, payload, payload_version, payload_hash,
                   updated_at, created_at
            FROM friendlysync_players
            WHERE LOWER(username) = LOWER(?)
            ORDER BY updated_at DESC
            LIMIT 1;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSet(rs));
                }
            }
        }
        return Optional.empty();
    }


    /**
     * Atomically attempts to acquire authoritative ownership of a player.
     * Uses atomic INSERT ... ON DUPLICATE KEY UPDATE followed by conditional atomic UPDATE with lease check.
     */
    public OwnershipResult acquireOwnership(
        UUID uuid,
        String username,
        String serverId,
        UUID sessionId,
        Duration leaseDuration
    ) {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        Preconditions.checkNotNull(sessionId, "sessionId cannot be null");
        Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");

        Instant leaseExpiry = Instant.now().plus(leaseDuration);
        Timestamp leaseTimestamp = Timestamp.from(leaseExpiry);

        try (Connection conn = databaseManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Step 1: Ensure row exists
                String insertSql = """
                    INSERT INTO friendlysync_players
                        (uuid, username, revision, fencing_token, active_session_id, owner_server,
                         session_state, lease_expires_at, payload, payload_version, payload_hash,
                         updated_at, created_at)
                    VALUES (?, ?, 0, 0, NULL, NULL, 'OFFLINE', NULL, NULL, 1, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE username = VALUES(username);
                    """;
                try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    insertPs.setQueryTimeout(10);
                    insertPs.setString(1, uuid.toString());
                    insertPs.setString(2, username != null ? username : uuid.toString());
                    insertPs.executeUpdate();
                }

                String updateSql = """
                    UPDATE friendlysync_players
                    SET owner_server = ?,
                        active_session_id = ?,
                        fencing_token = fencing_token + 1,
                        session_state = 'ACQUIRING',
                        lease_expires_at = ?,
                        username = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE uuid = ?
                      AND (
                          owner_server IS NULL
                          OR session_state IN ('OFFLINE', 'RELEASED')
                          OR (lease_expires_at < CURRENT_TIMESTAMP
                              AND session_state IN ('ACQUIRING', 'LOADING'))
                      );
                    """;
                int affectedRows;
                try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                    updatePs.setQueryTimeout(10);
                    updatePs.setString(1, serverId);
                    updatePs.setString(2, sessionId.toString());
                    updatePs.setTimestamp(3, leaseTimestamp);
                    updatePs.setString(4, username != null ? username : uuid.toString());
                    updatePs.setString(5, uuid.toString());
                    affectedRows = updatePs.executeUpdate();
                }

                if (affectedRows == 1) {
                    // Fetch the newly incremented fencing token and current revision
                    String fetchSql = """
                        SELECT revision, fencing_token
                        FROM friendlysync_players
                        WHERE uuid = ? AND active_session_id = ?;
                        """;
                    try (PreparedStatement fetchPs = conn.prepareStatement(fetchSql)) {
                        fetchPs.setQueryTimeout(10);
                        fetchPs.setString(1, uuid.toString());
                        fetchPs.setString(2, sessionId.toString());
                        try (ResultSet rs = fetchPs.executeQuery()) {
                            if (rs.next()) {
                                long revision = rs.getLong("revision");
                                long fencingToken = rs.getLong("fencing_token");
                                conn.commit();
                                return new OwnershipResult.Acquired(
                                    new SessionIdentity(uuid, sessionId, serverId, fencingToken),
                                    revision
                                );
                            }
                        }
                    }
                }

                // Ownership could not be acquired because another active server holds it
                conn.rollback();
                String checkSql = """
                    SELECT owner_server, lease_expires_at, fencing_token, session_state
                    FROM friendlysync_players
                    WHERE uuid = ?;
                    """;
                try (PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
                    checkPs.setQueryTimeout(10);
                    checkPs.setString(1, uuid.toString());
                    try (ResultSet rs = checkPs.executeQuery()) {
                        if (rs.next()) {
                            String currentOwner = rs.getString("owner_server");
                            Timestamp expiry = rs.getTimestamp("lease_expires_at");
                            long token = rs.getLong("fencing_token");
                            String state = rs.getString("session_state");
                            return new OwnershipResult.AlreadyOwned(
                                currentOwner,
                                expiry != null ? expiry.toInstant() : Instant.now(),
                                token,
                                state
                            );
                        }
                    }
                }

                return new OwnershipResult.Conflict("Failed to acquire ownership and row state could not be verified");
            } catch (Exception e) {
                conn.rollback();
                LOGGER.error("SQL error during ownership acquisition for {}: {}", uuid, e.getMessage(), e);
                return new OwnershipResult.DatabaseUnavailable(e);
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to obtain database connection for ownership acquisition: {}", e.getMessage(), e);
            return new OwnershipResult.DatabaseUnavailable(e);
        }
    }

    /**
     * Renews the session lease expiration.
     */
    public boolean renewLease(UUID uuid, UUID sessionId, long fencingToken, Duration leaseDuration) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        Preconditions.checkNotNull(sessionId, "sessionId cannot be null");

        Timestamp newExpiry = Timestamp.from(Instant.now().plus(leaseDuration));
        String sql = """
            UPDATE friendlysync_players
            SET lease_expires_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND active_session_id = ?
              AND fencing_token = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setTimestamp(1, newExpiry);
            ps.setString(2, uuid.toString());
            ps.setString(3, sessionId.toString());
            ps.setLong(4, fencingToken);
            return ps.executeUpdate() == 1;
        }
    }

    /** Small autocommitted updates release locks before verification or network round trips. */
    public Set<UUID> renewLeases(Collection<ActivePlayerSession> sessions, Duration leaseDuration) throws SQLException {
        if (sessions == null || sessions.isEmpty()) return Collections.emptySet();
        List<ActivePlayerSession> all = new ArrayList<>(sessions);
        all.sort(java.util.Comparator.comparing(s -> s.getPlayerUuid().toString()));
        Set<UUID> renewed = new HashSet<>();
        try (Connection conn = databaseManager.getConnection()) {
            if (!conn.getAutoCommit()) throw new SQLException("Lease renewal requires an autocommit connection");
            for (int offset = 0; offset < all.size(); offset += 16) {
                List<ActivePlayerSession> group = all.subList(offset, Math.min(offset + 16, all.size()));
                String predicate = "(" + String.join(" OR ", Collections.nCopies(group.size(),
                    "(uuid = ? AND active_session_id = ? AND fencing_token = ? AND owner_server = ?)")) + ")";
                // Match SQL timestamp precision so verification works with TIMESTAMP(0).
                Timestamp expiry = Timestamp.from(Instant.now().plus(leaseDuration).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
                try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE friendlysync_players SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP WHERE " + predicate)) {
                    ps.setQueryTimeout(10);
                    ps.setTimestamp(1, expiry);
                    bindLeaseOwners(ps, group, 2);
                    ps.executeUpdate();
                }
                // This non-locking read does not hold up player saves. A release or a
                // newer owner between update and read must not be reported as renewed.
                try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid FROM friendlysync_players WHERE " + predicate + " AND lease_expires_at >= ?")) {
                    ps.setQueryTimeout(10);
                    bindLeaseOwners(ps, group, 1);
                    ps.setTimestamp(group.size() * 4 + 1, expiry);
                    try (ResultSet rows = ps.executeQuery()) {
                        while (rows.next()) renewed.add(UUID.fromString(rows.getString("uuid")));
                    }
                }
            }
        }
        return renewed;
    }

    private static void bindLeaseOwners(PreparedStatement ps, List<ActivePlayerSession> sessions, int parameter) throws SQLException {
        for (ActivePlayerSession session : sessions) {
            ps.setString(parameter++, session.getPlayerUuid().toString());
            ps.setString(parameter++, session.getSessionId().toString());
            ps.setLong(parameter++, session.getFencingToken());
            ps.setString(parameter++, session.getServerId());
        }
    }


    /**
     * Atomically commits a new player state snapshot using optimistic concurrency control.
     * Fencing token, active session ID, and expected revision MUST match.
     */
    public CommitResult commit(
        UUID uuid,
        UUID sessionId,
        long fencingToken,
        long expectedRevision,
        byte[] payload,
        int payloadVersion,
        String payloadHash
    ) {
        return commit(uuid, sessionId, fencingToken, expectedRevision, payload, payloadVersion, payloadHash, "SYNC", null);
    }

    public CommitResult commit(
        UUID uuid,
        UUID sessionId,
        long fencingToken,
        long expectedRevision,
        byte[] payload,
        int payloadVersion,
        String payloadHash,
        String syncReason,
        String serverId
    ) {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        Preconditions.checkNotNull(sessionId, "sessionId cannot be null");
        Preconditions.checkNotNull(payload, "payload cannot be null");

        long start = System.nanoTime();
        String sql = """
            UPDATE friendlysync_players
            SET payload = ?,
                revision = revision + 1,
                payload_version = ?,
                payload_hash = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND revision = ?
              AND fencing_token = ?
              AND active_session_id = ?
              AND owner_server IS NOT NULL;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setBytes(1, payload);
            ps.setInt(2, payloadVersion);
            ps.setString(3, payloadHash);
            ps.setString(4, uuid.toString());
            ps.setLong(5, expectedRevision);
            ps.setLong(6, fencingToken);
            ps.setString(7, sessionId.toString());

            // Count attempted uploads too: failed/conflicting writes can still consume bandwidth.
            databaseManager.recordPayloadUpload(payload.length);
            int updated = ps.executeUpdate();

            if (snapshotRepository != null && isSnapshotEligible(syncReason)) {
                try {
                    String status = (updated == 1) ? "COMMITTED" : "SUPERSEDED";
                    long recordRev = (updated == 1) ? expectedRevision + 1 : expectedRevision;
                    PlayerSnapshotRecord snapshotRecord = new PlayerSnapshotRecord(
                        UUID.randomUUID().toString(),
                        uuid,
                        recordRev,
                        fencingToken,
                        sessionId,
                        serverId,
                        syncReason != null ? syncReason : "SYNC",
                        status,
                        payload,
                        payloadVersion,
                        payloadHash,
                        Instant.now()
                    );
                    // Copy confirmed bytes inside SQL. A superseded write still needs its
                    // original bytes uploaded because they are absent from the current row.
                    if (updated != 1 || !snapshotRepository.copyCommittedSnapshot(conn, snapshotRecord)) {
                        snapshotRepository.insertSnapshot(conn, snapshotRecord);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Failed to insert snapshot audit record for {}: {}", uuid, ex.getMessage());
                }
            }

            if (updated == 1) {
                return new CommitResult.Success(expectedRevision + 1,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }

            String actualRev = "?", actualToken = "?", actualSession = "?", actualOwner = "?", actualState = "?", actualLease = "?";
            long conflictDbRevision = -1;
            long conflictDbFencingToken = -1;
            String inspectSql = """
                SELECT revision, fencing_token, active_session_id, owner_server, session_state, lease_expires_at
                FROM friendlysync_players
                WHERE uuid = ?;
                """;
            try (PreparedStatement inspectPs = conn.prepareStatement(inspectSql)) {
                inspectPs.setQueryTimeout(10);
                inspectPs.setString(1, uuid.toString());
                try (ResultSet rs = inspectPs.executeQuery()) {
                    if (rs.next()) {
                        long dbRev = rs.getLong("revision");
                        long dbToken = rs.getLong("fencing_token");
                        conflictDbRevision = dbRev;
                        conflictDbFencingToken = dbToken;
                        actualRev = String.valueOf(dbRev);
                        actualToken = String.valueOf(dbToken);
                        actualSession = rs.getString("active_session_id");
                        actualOwner = rs.getString("owner_server");
                        actualState = rs.getString("session_state");
                        Timestamp leaseTs = rs.getTimestamp("lease_expires_at");
                        actualLease = leaseTs != null ? leaseTs.toInstant().toString() : "null";
                    }
                }
            } catch (SQLException ex) {
                LOGGER.debug("Could not inspect current row for player {}: {}", uuid, ex.getMessage());
            }

            LOGGER.warn("[CONFLICT] Commit rejected for player {}. Expected rev={}, token={}, session={}. Actual DB state: rev={}, token={}, session={}, owner={}, state={}, leaseExpires={}.",
                uuid, expectedRevision, fencingToken, sessionId,
                actualRev, actualToken, actualSession, actualOwner, actualState, actualLease);
            return new CommitResult.Conflict(
                conflictDbRevision >= 0 ? conflictDbRevision : expectedRevision,
                conflictDbFencingToken >= 0 ? conflictDbFencingToken : fencingToken,
                "Atomic commit matched 0 rows. Ownership was released or the revision, fencing token, or active session ID was superseded."
            );
        } catch (SQLException e) {
            LOGGER.error("Database error during commit for player {}: {}", uuid, e.getMessage(), e);
            return new CommitResult.Failed(e);
        }
    }

    /** Atomically replaces an offline profile and retains both sides of the restore in history. */
    public CommitResult restoreSnapshot(PersistedPlayerState expected,
                                        byte[] payload, int payloadVersion, String payloadHash) {
        Preconditions.checkNotNull(expected, "expected state cannot be null");
        Preconditions.checkNotNull(payload, "payload cannot be null");
        if (snapshotRepository == null) {
            return new CommitResult.Failed(new IllegalStateException("Snapshot history is required for restore backups"));
        }
        if (expected.ownerServer() != null
            || !("OFFLINE".equals(expected.sessionState()) || "RELEASED".equals(expected.sessionState()))) {
            return new CommitResult.Conflict(expected.revision(), expected.fencingToken(), "Player is owned by another or unsafe session");
        }

        long start = System.nanoTime();
        long token = Math.addExact(expected.fencingToken(), 1);
        String sql = """
            UPDATE friendlysync_players
            SET payload = ?, payload_version = ?, payload_hash = ?, revision = revision + 1,
                fencing_token = ?, updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ? AND revision = ? AND fencing_token = ?
              AND (active_session_id = ? OR (active_session_id IS NULL AND ? IS NULL))
              AND owner_server IS NULL AND session_state = ?;
            """;
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setQueryTimeout(10);
                    ps.setBytes(1, payload);
                    ps.setInt(2, payloadVersion);
                    ps.setString(3, payloadHash);
                    ps.setLong(4, token);
                    ps.setString(5, expected.uuid().toString());
                    ps.setLong(6, expected.revision());
                    ps.setLong(7, expected.fencingToken());
                    String session = expected.activeSessionId() != null ? expected.activeSessionId().toString() : null;
                    ps.setString(8, session);
                    ps.setString(9, session);
                    ps.setString(10, expected.sessionState());
                    databaseManager.recordPayloadUpload(payload.length);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return new CommitResult.Conflict(expected.revision(), expected.fencingToken(), "Player state changed before restore");
                    }
                }
                // Both history records and the replacement commit together, or nothing changes.
                if (expected.payload() != null) {
                    snapshotRepository.insertSnapshot(conn, new PlayerSnapshotRecord(UUID.randomUUID().toString(),
                        expected.uuid(), expected.revision(), expected.fencingToken(), expected.activeSessionId(),
                        expected.ownerServer(), "ADMIN_RESTORE_BACKUP", "COMMITTED", expected.payload(),
                        expected.payloadVersion(), expected.payloadHash(), Instant.now()));
                }
                snapshotRepository.insertSnapshot(conn, new PlayerSnapshotRecord(UUID.randomUUID().toString(),
                    expected.uuid(), Math.addExact(expected.revision(), 1), token, expected.activeSessionId(),
                    expected.ownerServer(), "ADMIN_RESTORE", "COMMITTED", payload, payloadVersion, payloadHash, Instant.now()));
                conn.commit();
                return new CommitResult.Success(expected.revision() + 1,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            } catch (Exception error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception error) {
            LOGGER.error("Cannot restore snapshot for {}", expected.uuid(), error);
            return new CommitResult.Failed(error);
        }
    }

    /** Updates durable session state without changing ownership. */
    public boolean updateSessionState(UUID uuid, UUID sessionId, long fencingToken, String newState) throws SQLException {
        String sql = """
            UPDATE friendlysync_players
            SET session_state = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND active_session_id = ?
              AND fencing_token = ?
              AND owner_server IS NOT NULL;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, newState);
            ps.setString(2, uuid.toString());
            ps.setString(3, sessionId.toString());
            ps.setLong(4, fencingToken);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Releases ownership of the player session.
     */
    public boolean releaseOwnership(UUID uuid, UUID sessionId, long fencingToken, String finalState) throws SQLException {
        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL,
                session_state = ?,
                lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND active_session_id = ?
              AND fencing_token = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, finalState);
            ps.setString(2, uuid.toString());
            ps.setString(3, sessionId.toString());
            ps.setLong(4, fencingToken);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Safely releases ownership if the record is currently owned by the specified server.
     */
    public boolean releaseOwnershipIfOwner(UUID uuid, String serverId, String finalState) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");

        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL,
                session_state = ?,
                lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND owner_server = ?
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
              AND session_state IN ('ACQUIRING', 'LOADING', 'OFFLINE', 'RELEASED');
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, finalState);
            ps.setString(2, uuid.toString());
            ps.setString(3, serverId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Reclaims any orphaned sessions previously owned by this server whose leases have expired.
     * Called during server startup to clean up leftover state from an unclean shutdown.
     */
    public int reclaimOrphanedServerSessions(String serverId) throws SQLException {
        return reclaimOrphanedServerSessions(serverId, java.util.Set.of());
    }

    /**
     * Startup only, after final-save journal recovery: this JVM holds no live sessions, so every
     * expired row it owns (any state) has nothing newer than SQL. Rows with an unresolved local
     * journal entry stay owned for review. The fencing bump rejects any write from the old run.
     */
    public int reclaimOrphanedServerSessions(String serverId, java.util.Set<UUID> retained) throws SQLException {
        Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");
        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL,
                active_session_id = NULL,
                session_state = 'OFFLINE',
                lease_expires_at = NULL,
                fencing_token = fencing_token + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE owner_server = ?
              AND lease_expires_at < CURRENT_TIMESTAMP
            """ + (retained.isEmpty() ? "" : " AND uuid NOT IN (" + String.join(",", java.util.Collections.nCopies(retained.size(), "?")) + ")");
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverId);
            int i = 2;
            for (UUID uuid : retained) ps.setString(i++, uuid.toString());
            ps.setQueryTimeout(10);
            return ps.executeUpdate();
        }
    }

    /**
     * Releases the exact observed lease (any state) only when it expired more than {@code deadAfter}
     * ago and its owner server has not heartbeated presence for {@code deadAfter}. The fencing bump
     * rejects any late write if that server was only partitioned rather than dead.
     */
    public boolean releaseAbandonedLease(UUID uuid, String owner, Instant observedExpiry, Duration deadAfter) throws SQLException {
        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL, active_session_id = NULL, session_state = 'OFFLINE',
                lease_expires_at = NULL, fencing_token = fencing_token + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ? AND owner_server = ? AND lease_expires_at = ?
              AND lease_expires_at < TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)
              AND NOT EXISTS (
                  SELECT 1 FROM friendlysync_server_presence
                  WHERE server_id = ? AND last_heartbeat >= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP))
            """;
        long seconds = -Math.max(1L, deadAfter.toSeconds());
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            ps.setString(2, owner);
            ps.setTimestamp(3, Timestamp.from(observedExpiry));
            ps.setLong(4, seconds);
            ps.setString(5, owner);
            ps.setLong(6, seconds);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Safely clears an expired lease abandoned by another server that failed to release it
     * after retry deadlines. Optimistically checks that the lease timestamp hasn't been renewed.
     */
    public boolean breakExpiredLease(UUID uuid, String expectedOwner, Instant expectedExpiry) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        if (expectedOwner == null || expectedOwner.isBlank()) {
            return false;
        }
        Preconditions.checkNotNull(expectedExpiry, "expectedExpiry cannot be null");

        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL,
                session_state = 'OFFLINE',
                lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?
              AND owner_server = ?
              AND lease_expires_at <= ?
              AND lease_expires_at < CURRENT_TIMESTAMP
              AND session_state IN ('ACQUIRING', 'LOADING');
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            ps.setString(2, expectedOwner);
            ps.setTimestamp(3, Timestamp.from(expectedExpiry));
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Fences only the exact expired ACTIVE row selected during explicit conflict review.
     * The caller must check the local player is offline and preserve both payloads first.
     */
    public boolean releaseReviewedConflict(PersistedPlayerState expected) throws SQLException {
        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL, active_session_id = NULL, session_state = 'OFFLINE',
                lease_expires_at = NULL, fencing_token = fencing_token + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ? AND owner_server = ? AND active_session_id = ?
              AND fencing_token = ? AND revision = ? AND payload_hash = ?
              AND session_state = 'ACTIVE' AND lease_expires_at = ?
              AND lease_expires_at < CURRENT_TIMESTAMP;
            """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, expected.uuid().toString());
            ps.setString(2, expected.ownerServer());
            ps.setString(3, expected.activeSessionId().toString());
            ps.setLong(4, expected.fencingToken());
            ps.setLong(5, expected.revision());
            ps.setString(6, expected.payloadHash());
            ps.setTimestamp(7, Timestamp.from(expected.leaseExpiresAt()));
            return ps.executeUpdate() == 1;
        }
    }

    /** Manual recovery only: SQL protects renewed leases and fences delayed writes. */
    public boolean unlockExpiredOwnership(UUID uuid) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL, active_session_id = NULL, session_state = 'OFFLINE',
                lease_expires_at = NULL, fencing_token = fencing_token + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ? AND owner_server IS NOT NULL
              AND lease_expires_at < CURRENT_TIMESTAMP;
            """;
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Unconditionally releases ownership for legacy callers. */
    public boolean forceReleaseOwnership(UUID uuid, String finalState) throws SQLException {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");

        String sql = """
            UPDATE friendlysync_players
            SET owner_server = NULL,
                session_state = ?,
                lease_expires_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE uuid = ?;
            """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            ps.setString(1, finalState);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }


    private PersistedPlayerState mapResultSet(ResultSet rs) throws SQLException {
        String activeSessionStr = rs.getString("active_session_id");
        Timestamp leaseTs = rs.getTimestamp("lease_expires_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Timestamp createdTs = rs.getTimestamp("created_at");

        byte[] payload = rs.getBytes("payload");
        if (payload != null) databaseManager.recordPayloadDownload(payload.length);
        return new PersistedPlayerState(
            UUID.fromString(rs.getString("uuid")),
            rs.getString("username"),
            rs.getLong("revision"),
            rs.getLong("fencing_token"),
            activeSessionStr != null ? UUID.fromString(activeSessionStr) : null,
            rs.getString("owner_server"),
            rs.getString("session_state"),
            leaseTs != null ? leaseTs.toInstant() : null,
            payload,
            rs.getInt("payload_version"),
            rs.getString("payload_hash"),
            updatedTs != null ? updatedTs.toInstant() : Instant.now(),
            createdTs != null ? createdTs.toInstant() : Instant.now()
        );
    }
}
