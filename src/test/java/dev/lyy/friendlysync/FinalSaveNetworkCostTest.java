package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRepository;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.session.FinalSaveJournal;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FinalSaveNetworkCostTest {
    @TempDir Path directory;
    final AtomicLong uploadedBytes = new AtomicLong();
    final AtomicLong downloadedBytes = new AtomicLong();
    PlayerStateRepository states;
    PlayerSnapshotRepository snapshots;
    DatabaseManager database;
    String adminUrl;
    String temporaryDatabase;

    @BeforeEach void setup() throws Exception {
        String url = "jdbc:h2:mem:network_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        // Optional integration run against an explicitly supplied disposable local instance.
        adminUrl = System.getenv("FRIENDLYSYNC_TEST_MYSQL_URL");
        if (adminUrl != null && !adminUrl.isBlank()) {
            if (!adminUrl.startsWith("jdbc:mysql://127.0.0.1:13316/")) {
                throw new IllegalArgumentException("Integration tests require the disposable localhost:13316 MySQL instance");
            }
            temporaryDatabase = "fsync_network_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection admin = DriverManager.getConnection(adminUrl); var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + temporaryDatabase);
            }
            url = "jdbc:mysql://127.0.0.1:13316/" + temporaryDatabase
                + "?user=root&useSSL=false&allowPublicKeyRetrieval=true&useServerPrepStmts=true";
        }
        String testUrl = url;
        database = mock(DatabaseManager.class);
        when(database.getConnection()).thenAnswer(call -> countedConnection(DriverManager.getConnection(testUrl)));
        try (Connection conn = database.getConnection(); var statement = conn.createStatement()) {
            for (String resource : new String[]{"db/migration/V1__initial_schema.sql", "db/migration/V4__add_player_snapshots_table.sql"}) {
                try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                    String ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    for (String sql : ddl.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
            }
        }
        snapshots = new PlayerSnapshotRepository(database);
        states = new PlayerStateRepository(database, snapshots);
    }

    @AfterEach void cleanup() throws Exception {
        if (temporaryDatabase != null) {
            try (Connection admin = DriverManager.getConnection(adminUrl); var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE " + temporaryDatabase);
            }
        } else if (database != null) {
            try (Connection conn = database.getConnection(); var statement = conn.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    // Counts application payload bytes bound/read through JDBC, not protocol headers
    // or measured NIC bandwidth. Real H2 statements still execute the storage logic.
    Connection countedConnection(Connection connection) throws Exception {
        Connection counted = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(connection));
        doAnswer(call -> {
            PreparedStatement statement = connection.prepareStatement(call.getArgument(0));
            PreparedStatement wrapped = mock(PreparedStatement.class, org.mockito.AdditionalAnswers.delegatesTo(statement));
            doAnswer(bind -> {
                byte[] bytes = bind.getArgument(1);
                uploadedBytes.addAndGet(bytes.length);
                statement.setBytes(bind.getArgument(0), bytes);
                return null;
            }).when(wrapped).setBytes(anyInt(), any(byte[].class));
            doAnswer(query -> {
                ResultSet rows = statement.executeQuery();
                ResultSet countedRows = mock(ResultSet.class, org.mockito.AdditionalAnswers.delegatesTo(rows));
                doAnswer(read -> {
                    byte[] bytes = rows.getBytes(read.getArgument(0, String.class));
                    if (bytes != null) downloadedBytes.addAndGet(bytes.length);
                    return bytes;
                }).when(countedRows).getBytes(anyString());
                return countedRows;
            }).when(wrapped).executeQuery();
            return wrapped;
        }).when(counted).prepareStatement(anyString());
        return counted;
    }

    @Test void fortyFinalSavesUploadEachPayloadOnceWithoutDownloadingOldPayloads() throws Exception {
        byte[] payload = new byte[128 * 1024];
        new java.util.Random(42).nextBytes(payload);
        String hash = DigestUtils.sha256(payload);
        var entries = new ArrayList<FinalSaveJournal.Entry>();
        for (int i = 0; i < 40; i++) {
            UUID uuid = UUID.randomUUID();
            var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player" + i, "spawn-a",
                UUID.randomUUID(), Duration.ofSeconds(45));
            var id = acquired.session();
            assertThat(states.commit(uuid, id.sessionId(), id.fencingToken(), 0, payload, 3, hash))
                .isInstanceOf(CommitResult.Success.class);
            entries.add(new FinalSaveJournal.Entry(id, 1, 3, payload, hash));
        }
        uploadedBytes.set(0);
        downloadedBytes.set(0);
        var journal = new FinalSaveJournal(directory, states);
        for (var entry : entries) assertThat(journal.commit(entry)).isInstanceOf(CommitResult.Success.class);
        assertThat(uploadedBytes.get()).isEqualTo(40L * payload.length);
        assertThat(downloadedBytes.get()).isZero();
        // Lost acknowledgement: retry recognizes the exact commit without another upload.
        for (var entry : entries) assertThat(journal.commit(entry)).isInstanceOf(CommitResult.Success.class);
        assertThat(uploadedBytes.get()).isEqualTo(40L * payload.length);
        assertThat(downloadedBytes.get()).isZero();
        for (var entry : entries) {
            var stored = snapshots.findLatestSnapshot(entry.identity().playerUuid()).orElseThrow();
            assertThat(stored.payload()).isEqualTo(payload);
            assertThat(stored.revision()).isEqualTo(2);
            assertThat(stored.status()).isEqualTo("COMMITTED");
        }
    }

    @Test void historyCopyRejectsChangedRevisionTokenSessionOrHash() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player", "spawn-a",
            UUID.randomUUID(), Duration.ofSeconds(45));
        var id = acquired.session();
        byte[] bytes = {1, 2, 3};
        String hash = DigestUtils.sha256(bytes);
        states.commit(uuid, id.sessionId(), id.fencingToken(), 0, bytes, 3, hash);
        try (Connection conn = database.getConnection()) {
            for (int mismatch = 0; mismatch < 5; mismatch++) {
                var record = new PlayerSnapshotRecord(UUID.randomUUID().toString(), uuid,
                    mismatch == 0 ? 2 : 1, mismatch == 1 ? id.fencingToken() + 1 : id.fencingToken(),
                    mismatch == 2 ? UUID.randomUUID() : id.sessionId(), "spawn-a", "FINAL_QUIT", "COMMITTED",
                    bytes, mismatch == 4 ? 99 : 3, mismatch == 3 ? "wrong" : hash, Instant.now());
                assertThat(snapshots.copyCommittedSnapshot(conn, record)).isFalse();
            }
        }
        assertThat(snapshots.findLatestSnapshot(uuid)).isEmpty();
    }

    @Test void concurrentRowChangeFallsBackToOriginalCapturedBytes() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player", "spawn-a",
            UUID.randomUUID(), Duration.ofSeconds(45));
        var id = acquired.session();
        byte[] original = {1, 2, 3};
        byte[] newer = {9, 8, 7};
        var racingSnapshots = spy(snapshots);
        doAnswer(call -> {
            states.commit(uuid, id.sessionId(), id.fencingToken(), 1, newer, 3, DigestUtils.sha256(newer));
            return snapshots.copyCommittedSnapshot(call.getArgument(0), call.getArgument(1));
        }).when(racingSnapshots).copyCommittedSnapshot(any(), any());
        var racingStates = new PlayerStateRepository(database, racingSnapshots);
        assertThat(racingStates.commit(uuid, id.sessionId(), id.fencingToken(), 0,
            original, 3, DigestUtils.sha256(original), "FINAL_QUIT", "spawn-a"))
            .isInstanceOf(CommitResult.Success.class);
        assertThat(states.find(uuid).orElseThrow().payload()).isEqualTo(newer);
        assertThat(snapshots.findLatestSnapshot(uuid).orElseThrow().payload()).isEqualTo(original);
    }

    @Test void expiredGameplayOwnershipIsOnlyReclaimedByItsRestartedOwner() throws Exception {
        Set<UUID> retained = new java.util.HashSet<>();
        for (String state : new String[]{"ACTIVE", "FREEZING", "SAVING", "TRANSFERRING", "FAILED"}) {
            UUID uuid = UUID.randomUUID();
            UUID journaled = UUID.randomUUID();
            retained.add(journaled);
            for (UUID id : new UUID[]{uuid, journaled}) {
                var acquired = (OwnershipResult.Acquired) states.acquireOwnership(id, "Player", "spawn-a",
                    UUID.randomUUID(), Duration.ofSeconds(45));
                states.updateSessionState(id, acquired.session().sessionId(), acquired.session().fencingToken(), state);
                try (Connection conn = database.getConnection(); var ps = conn.prepareStatement(
                    "UPDATE friendlysync_players SET lease_expires_at = TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    ps.executeUpdate();
                }
            }
            // Other servers must never break a gameplay lease.
            assertThat(states.breakExpiredLease(uuid, "spawn-a", Instant.now())).isFalse();
            assertThat(states.breakExpiredLease(uuid, null, Instant.now())).isFalse();
            assertThat(states.acquireOwnership(uuid, "Player", "economy-a", UUID.randomUUID(), Duration.ofSeconds(45)))
                .isInstanceOf(OwnershipResult.AlreadyOwned.class);
            assertThat(states.reclaimOrphanedServerSessions("economy-a")).isZero();

            // The restarted owner releases its rows, except ones with an unresolved local journal.
            assertThat(states.reclaimOrphanedServerSessions("spawn-a", retained)).isEqualTo(1);
            assertThat(states.acquireOwnership(uuid, "Player", "economy-a", UUID.randomUUID(), Duration.ofSeconds(45)))
                .isInstanceOf(OwnershipResult.Acquired.class);
            assertThat(states.acquireOwnership(journaled, "Player", "economy-a", UUID.randomUUID(), Duration.ofSeconds(45)))
                .isInstanceOf(OwnershipResult.AlreadyOwned.class);
        }
    }

    @Test void abandonedLeaseIsReleasedOnlyWhenOwnerServerIsGone() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player", "economy-a",
            UUID.randomUUID(), Duration.ofSeconds(45));
        var id = acquired.session();
        states.updateSessionState(uuid, id.sessionId(), id.fencingToken(), "ACTIVE");
        Duration dead = Duration.ofMinutes(2);
        try (Connection conn = database.getConnection(); var statement = conn.createStatement()) {
            statement.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMPADD(SECOND, -30, CURRENT_TIMESTAMP) WHERE uuid = '" + uuid + "'");
        }
        // Expired only 30s ago: owner may just be lagging.
        assertThat(states.releaseAbandonedLease(uuid, "economy-a", states.find(uuid).orElseThrow().leaseExpiresAt(), dead)).isFalse();

        try (Connection conn = database.getConnection(); var statement = conn.createStatement()) {
            statement.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMPADD(SECOND, -600, CURRENT_TIMESTAMP) WHERE uuid = '" + uuid + "'");
            statement.executeUpdate("INSERT INTO friendlysync_server_presence (server_id, instance_id, plugin_version, minecraft_version, platform, last_heartbeat, started_at) "
                + "VALUES ('economy-a', '" + UUID.randomUUID() + "', 't', 't', 't', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        Instant expiry = states.find(uuid).orElseThrow().leaseExpiresAt();
        // Owner still heartbeating presence: alive, never take over.
        assertThat(states.releaseAbandonedLease(uuid, "economy-a", expiry, dead)).isFalse();

        try (Connection conn = database.getConnection(); var statement = conn.createStatement()) {
            statement.executeUpdate("UPDATE friendlysync_server_presence SET last_heartbeat = TIMESTAMPADD(SECOND, -600, CURRENT_TIMESTAMP) WHERE server_id = 'economy-a'");
        }
        // Lease was renewed since we observed it, or wrong owner: no-op.
        assertThat(states.releaseAbandonedLease(uuid, "economy-a", expiry.minusSeconds(1), dead)).isFalse();
        assertThat(states.releaseAbandonedLease(uuid, "spawn-a", expiry, dead)).isFalse();

        assertThat(states.releaseAbandonedLease(uuid, "economy-a", expiry, dead)).isTrue();
        // The dead owner's late writes are fenced off.
        byte[] late = {7};
        assertThat(states.commit(uuid, id.sessionId(), id.fencingToken(), acquired.currentRevision(), late, 3, DigestUtils.sha256(late)))
            .isNotInstanceOf(CommitResult.Success.class);
        assertThat(states.acquireOwnership(uuid, "Player", "spawn-a", UUID.randomUUID(), Duration.ofSeconds(45)))
            .isInstanceOf(OwnershipResult.Acquired.class);
    }

    @Test void staleJournalCannotOverwriteANewerCommitInTheSameSession() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player", "spawn-a",
            UUID.randomUUID(), Duration.ofSeconds(45));
        var id = acquired.session();
        byte[] old = {1}, latest = {2};
        states.commit(uuid, id.sessionId(), id.fencingToken(), 0, latest, 3, DigestUtils.sha256(latest));
        var journal = new FinalSaveJournal(directory, states);
        assertThat(journal.commit(new FinalSaveJournal.Entry(id, 0, 3, old, DigestUtils.sha256(old))))
            .isInstanceOf(CommitResult.Conflict.class);
        assertThat(states.find(uuid).orElseThrow().payload()).isEqualTo(latest);
    }

    @Test void recoveryRequiresExactCommittedHashAndRevision() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) states.acquireOwnership(uuid, "Player", "spawn-a",
            UUID.randomUUID(), Duration.ofSeconds(45));
        var id = acquired.session();
        byte[] bytes = {1, 2, 3};
        String hash = DigestUtils.sha256(bytes);
        states.commit(uuid, id.sessionId(), id.fencingToken(), 0, bytes, 3, hash, "FINAL_QUIT", "spawn-a");
        assertThat(snapshots.findExactCommittedSnapshot(uuid, 1, hash).orElseThrow().payload()).isEqualTo(bytes);
        assertThat(snapshots.findExactCommittedSnapshot(uuid, 2, hash)).isEmpty();
        assertThat(snapshots.findExactCommittedSnapshot(uuid, 1, "different")).isEmpty();
        String snapshotId = snapshots.findLatestSnapshot(uuid).orElseThrow().snapshotId();
        snapshots.updateSnapshotStatus(snapshotId, "SUPERSEDED");
        assertThat(snapshots.findExactCommittedSnapshot(uuid, 1, hash)).isEmpty();
    }
}
