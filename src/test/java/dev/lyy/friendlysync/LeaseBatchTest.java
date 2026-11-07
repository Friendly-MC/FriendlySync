package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.migration.DatabaseMigrator;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the core anti-duplication invariant:
 * Monotonic fencing tokens prevent stale zombie writers from committing updates
 * after another server has acquired ownership.
 */
public class LeaseBatchTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:friendlysync_lease_batch;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    @BeforeEach
    void setUp() throws Exception {
        rootConnection = DriverManager.getConnection(JDBC_URL, "sa", "");

        // Create table structure
        try (Statement stmt = rootConnection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS friendlysync_players (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    revision BIGINT NOT NULL DEFAULT 0,
                    fencing_token BIGINT NOT NULL DEFAULT 0,
                    active_session_id VARCHAR(36) NULL,
                    owner_server VARCHAR(64) NULL,
                    session_state VARCHAR(32) NOT NULL DEFAULT 'OFFLINE',
                    lease_expires_at TIMESTAMP NULL,
                    payload LONGBLOB NULL,
                    payload_version INT NOT NULL DEFAULT 1,
                    payload_hash VARCHAR(64) NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """);
        }

        mockDatabaseManager = Mockito.mock(DatabaseManager.class);
        Mockito.when(mockDatabaseManager.getConnection()).thenAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", ""));

        repository = new PlayerStateRepository(mockDatabaseManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (rootConnection != null && !rootConnection.isClosed()) {
            try (Statement stmt = rootConnection.createStatement()) {
                stmt.execute("DROP ALL OBJECTS");
            }
            rootConnection.close();
        }
    }

    private dev.lyy.friendlysync.model.ActivePlayerSession player() {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) repository.acquireOwnership(uuid, "Player", "source", UUID.randomUUID(), Duration.ofSeconds(1));
        return new dev.lyy.friendlysync.model.ActivePlayerSession(uuid, acquired.session().sessionId(), "source",
            acquired.session().fencingToken(), 0, "Player", dev.lyy.friendlysync.model.SessionState.ACTIVE);
    }

    @Test void mixedOwnersRenewOnlyExactLiveIdentities() throws Exception {
        var valid = player();
        var released = player();
        repository.releaseOwnership(released.getPlayerUuid(), released.getSessionId(), released.getFencingToken(), "OFFLINE");
        var stale = player();
        repository.releaseOwnership(stale.getPlayerUuid(), stale.getSessionId(), stale.getFencingToken(), "OFFLINE");
        var newer = (OwnershipResult.Acquired) repository.acquireOwnership(stale.getPlayerUuid(), "Player", "destination", UUID.randomUUID(), Duration.ofSeconds(1));
        var wrongServerRow = player();
        var wrongServer = new dev.lyy.friendlysync.model.ActivePlayerSession(wrongServerRow.getPlayerUuid(), wrongServerRow.getSessionId(), "wrong",
            wrongServerRow.getFencingToken(), 0, "Player", dev.lyy.friendlysync.model.SessionState.ACTIVE);
        var before = repository.find(stale.getPlayerUuid()).orElseThrow();
        var renewed = repository.renewLeases(java.util.List.of(valid, released, stale, wrongServer), Duration.ofSeconds(90));
        assertThat(renewed).containsExactly(valid.getPlayerUuid());
        assertThat(repository.find(valid.getPlayerUuid()).orElseThrow().leaseExpiresAt()).isAfter(java.time.Instant.now().plusSeconds(60));
        assertThat(repository.find(released.getPlayerUuid()).orElseThrow().leaseExpiresAt()).isNull();
        assertThat(repository.find(stale.getPlayerUuid()).orElseThrow()).isEqualTo(before);
        assertThat(repository.find(wrongServer.getPlayerUuid()).orElseThrow().leaseExpiresAt()).isBefore(java.time.Instant.now().plusSeconds(60));
    }

    @Test void groupsBoundStatementCountAndPreserveAllResults() throws Exception {
        var players = new java.util.ArrayList<dev.lyy.friendlysync.model.ActivePlayerSession>();
        for (int i = 0; i < 260; i++) players.add(player());
        var connection = Mockito.spy(DriverManager.getConnection(JDBC_URL, "sa", ""));
        Mockito.when(mockDatabaseManager.getConnection()).thenReturn(connection);
        assertThat(repository.renewLeases(players, Duration.ofSeconds(45))).hasSize(260);
        Mockito.verify(connection, Mockito.times(34)).prepareStatement(Mockito.anyString());
    }

    @Test void updateFailureClosesConnectionAndNeverReportsSuccess() throws Exception {
        var player = player();
        var before = repository.find(player.getPlayerUuid()).orElseThrow();
        var connection = Mockito.spy(DriverManager.getConnection(JDBC_URL, "sa", ""));
        Mockito.doThrow(new SQLException("simulated update failure")).when(connection)
            .prepareStatement(Mockito.startsWith("UPDATE friendlysync_players SET lease_expires_at"));
        Mockito.when(mockDatabaseManager.getConnection()).thenReturn(connection);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.renewLeases(java.util.List.of(player), Duration.ofSeconds(90)))
            .isInstanceOf(SQLException.class);
        Mockito.verify(connection).close();
        Mockito.when(mockDatabaseManager.getConnection()).thenAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", ""));
        assertThat(repository.find(player.getPlayerUuid()).orElseThrow()).isEqualTo(before);
    }
    @Test void concurrentReleaseBetweenUpdateAndReadIsNotReportedAsRenewed() throws Exception {
        var player = player();
        var connection = Mockito.spy(DriverManager.getConnection(JDBC_URL, "sa", ""));
        var releaseStarted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            Mockito.when(mockDatabaseManager.getConnection()).thenReturn(connection)
                .thenAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", ""));
            Mockito.doAnswer(inv -> {
                release.set(worker.submit(() -> {
                    releaseStarted.countDown();
                    return repository.releaseOwnership(player.getPlayerUuid(), player.getSessionId(), player.getFencingToken(), "OFFLINE");
                }));
                assertThat(releaseStarted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(release.get().get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return inv.callRealMethod();
            }).when(connection).prepareStatement(Mockito.startsWith("SELECT uuid FROM friendlysync_players"));
            assertThat(repository.renewLeases(java.util.List.of(player), Duration.ofSeconds(90))).isEmpty();
            assertThat(release.get().get(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var row = repository.find(player.getPlayerUuid()).orElseThrow();
            assertThat(row.ownerServer()).isNull();
            assertThat(row.leaseExpiresAt()).isNull();
        }
    }

}
