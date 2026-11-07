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
public class FencingTokenTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:friendlysync_fencing;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

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

    @Test
    @DisplayName("Mandatory Race Condition Test (Section 80): Stale writer token 100 is rejected after token 101 acquires")
    void testStaleWriterDelayedCommitRejected() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        String username = "Player1";

        // Step 1: Server A acquires ownership with token 1
        UUID sessionA = UUID.randomUUID();
        OwnershipResult resultA = repository.acquireOwnership(
            playerUuid, username, "server-A", sessionA, Duration.ofMillis(100)
        );
        assertThat(resultA).isInstanceOf(OwnershipResult.Acquired.class);
        OwnershipResult.Acquired acquiredA = (OwnershipResult.Acquired) resultA;
        long tokenA = acquiredA.session().fencingToken();
        long revA = acquiredA.currentRevision();

        assertThat(tokenA).isEqualTo(1L);
        assertThat(revA).isEqualTo(0L);

        // Step 2: Server A's initial commit advances revision to 1
        byte[] payloadA = "payload-A1".getBytes();
        CommitResult commitA1 = repository.commit(
            playerUuid, sessionA, tokenA, revA, payloadA, 1, "hashA1"
        );
        assertThat(commitA1).isInstanceOf(CommitResult.Success.class);
        assertThat(((CommitResult.Success) commitA1).newRevision()).isEqualTo(1L);

        // Step 3: Server A stops responding and its lease expires
        Thread.sleep(150);

        // Step 4: Server B acquires ownership with a newer fencing token
        UUID sessionB = UUID.randomUUID();
        OwnershipResult resultB = repository.acquireOwnership(
            playerUuid, username, "server-B", sessionB, Duration.ofSeconds(45)
        );
        assertThat(resultB).isInstanceOf(OwnershipResult.Acquired.class);
        OwnershipResult.Acquired acquiredB = (OwnershipResult.Acquired) resultB;
        long tokenB = acquiredB.session().fencingToken();
        long revB = acquiredB.currentRevision();

        assertThat(tokenB).isGreaterThan(tokenA);
        assertThat(revB).isEqualTo(1L);

        // Step 5: Server B commits new valid state (rev 2)
        byte[] payloadB = "payload-B2".getBytes();
        CommitResult commitB = repository.commit(
            playerUuid, sessionB, tokenB, revB, payloadB, 1, "hashB2"
        );
        assertThat(commitB).isInstanceOf(CommitResult.Success.class);
        assertThat(((CommitResult.Success) commitB).newRevision()).isEqualTo(2L);

        // Step 6: Server A returns late and attempts a delayed commit using stale tokenA and stale rev 1
        byte[] stalePayloadA = "payload-A-stale".getBytes();
        CommitResult staleCommitResult = repository.commit(
            playerUuid, sessionA, tokenA, 1L, stalePayloadA, 1, "hashAStale"
        );

        // Expected: Server A's commit is REJECTED
        assertThat(staleCommitResult).isInstanceOf(CommitResult.Conflict.class);

        // Verify Server B's committed state remains 100% intact
        var persisted = repository.find(playerUuid).orElseThrow();
        assertThat(persisted.revision()).isEqualTo(2L);
        assertThat(persisted.fencingToken()).isEqualTo(tokenB);
        assertThat(persisted.ownerServer()).isEqualTo("server-B");
        assertThat(persisted.payloadHash()).isEqualTo("hashB2");
        assertThat(persisted.payload()).isEqualTo(payloadB);
    }
}
