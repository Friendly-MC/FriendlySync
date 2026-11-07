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
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class CrashRecoveryTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:crash_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

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
    @DisplayName("Crash Recovery (Section 25): Stranded session with expired lease is automatically recovered by new server")
    void testCrashRecoveryAfterLeaseExpiration() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID crashedSession = UUID.randomUUID();

        // 1. Source server acquires with a normal 45s lease
        OwnershipResult res = repository.acquireOwnership(
            playerUuid, "CrashPlayer", "crashed-server", crashedSession, Duration.ofSeconds(45)
        );
        assertThat(res).isInstanceOf(OwnershipResult.Acquired.class);
        long tokenA = ((OwnershipResult.Acquired) res).session().fencingToken();

        // Commit initial state
        repository.commit(playerUuid, crashedSession, tokenA, 0L, "saved-data".getBytes(), 1, "h1");

        // 2. Destination server attempts to acquire while lease is active: MUST be denied
        UUID destSession = UUID.randomUUID();
        OwnershipResult denied = repository.acquireOwnership(
            playerUuid, "CrashPlayer", "dest-server", destSession, Duration.ofSeconds(45)
        );
        assertThat(denied).isInstanceOf(OwnershipResult.AlreadyOwned.class);
        assertThat(((OwnershipResult.AlreadyOwned) denied).ownerServer()).isEqualTo("crashed-server");

        // 3. Simulate source server crashing and lease timeout expiring
        try (Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
             PreparedStatement ps = conn.prepareStatement("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            ps.setInt(1, -10);
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }

        // 4. Destination re-attempts after lease expiry: granted!
        OwnershipResult recovered = repository.acquireOwnership(
            playerUuid, "CrashPlayer", "dest-server", destSession, Duration.ofSeconds(45)
        );
        assertThat(recovered).isInstanceOf(OwnershipResult.Acquired.class);
        long tokenB = ((OwnershipResult.Acquired) recovered).session().fencingToken();

        // Fencing token MUST increment to protect against the dead server rising again
        assertThat(tokenB).isGreaterThan(tokenA);
        assertThat(((OwnershipResult.Acquired) recovered).currentRevision()).isEqualTo(1L);
    }
}
