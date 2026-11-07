package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class RevisionCasTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:revision_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @BeforeEach
    void setUp() throws Exception {
        rootConnection = DriverManager.getConnection(JDBC_URL, "sa", "");
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
    @DisplayName("Optimistic Concurrency: Committing with wrong revision is rejected as conflict")
    void testRevisionMismatchRejected() {
        UUID uuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        OwnershipResult acq = repository.acquireOwnership(uuid, "TestUser", "server-1", sessionId, Duration.ofSeconds(30));
        assertThat(acq).isInstanceOf(OwnershipResult.Acquired.class);
        long token = ((OwnershipResult.Acquired) acq).session().fencingToken();

        // Successful commit advances rev from 0 to 1
        CommitResult res1 = repository.commit(uuid, sessionId, token, 0L, "payload-1".getBytes(), 1, "h1");
        assertThat(res1).isInstanceOf(CommitResult.Success.class);
        assertThat(((CommitResult.Success) res1).newRevision()).isEqualTo(1L);

        // Attempting to commit again with stale expected revision 0 MUST be rejected
        CommitResult staleRes = repository.commit(uuid, sessionId, token, 0L, "payload-stale".getBytes(), 1, "h-stale");
        assertThat(staleRes).isInstanceOf(CommitResult.Conflict.class);

        // Attempting with future revision 5 MUST also be rejected
        CommitResult futureRes = repository.commit(uuid, sessionId, token, 5L, "payload-future".getBytes(), 1, "h-future");
        assertThat(futureRes).isInstanceOf(CommitResult.Conflict.class);

        // Attempting with correct revision 1 advances to 2
        CommitResult res2 = repository.commit(uuid, sessionId, token, 1L, "payload-2".getBytes(), 1, "h2");
        assertThat(res2).isInstanceOf(CommitResult.Success.class);
        assertThat(((CommitResult.Success) res2).newRevision()).isEqualTo(2L);
    }
}
