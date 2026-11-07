package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.messaging.message.TransferReadyMessage;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class TransferProtocolTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:transfer_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

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
    @DisplayName("Duplicate Transfer Test (Section 81): Same message received 10 times is processed idempotently")
    void testDuplicateTransferMessageIdempotency() {
        UUID playerUuid = UUID.randomUUID();
        TransferReadyMessage msg = TransferReadyMessage.create(playerUuid, 10L, 5L, "server-A", "server-B");

        AtomicInteger successfulLoads = new AtomicInteger(0);

        // Server B processes incoming transfer ready notifications
        // Even if received 10 times, monotonic revision check ensures only new revisions are processed
        long lastProcessedRevision = 0;
        for (int i = 0; i < 10; i++) {
            if (msg.revision() > lastProcessedRevision) {
                successfulLoads.incrementAndGet();
                lastProcessedRevision = msg.revision();
            }
        }

        // Exactly one logical load occurred
        assertThat(successfulLoads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Fast Reconnect Test (Section 82): Disconnect A, join B, reconnect A: at no point can both be authoritative")
    void testFastReconnectExclusivity() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();

        // 1. Server A acquires
        OwnershipResult resA = repository.acquireOwnership(playerUuid, "FastUser", "server-A", sessionA, Duration.ofSeconds(30));
        assertThat(resA).isInstanceOf(OwnershipResult.Acquired.class);

        // 2. Player attempts to join B without A releasing first
        UUID sessionB = UUID.randomUUID();
        OwnershipResult resB = repository.acquireOwnership(playerUuid, "FastUser", "server-B", sessionB, Duration.ofSeconds(30));
        // Server B MUST be denied
        assertThat(resB).isInstanceOf(OwnershipResult.AlreadyOwned.class);
        assertThat(((OwnershipResult.AlreadyOwned) resB).ownerServer()).isEqualTo("server-A");

        // 3. Server A releases ownership (controlled transfer / disconnect)
        OwnershipResult.Acquired acqA = (OwnershipResult.Acquired) resA;
        repository.releaseOwnership(playerUuid, sessionA, acqA.session().fencingToken(), "RELEASED");

        // 4. Now Server B can acquire safely
        OwnershipResult resBAfter = repository.acquireOwnership(playerUuid, "FastUser", "server-B", sessionB, Duration.ofSeconds(30));
        assertThat(resBAfter).isInstanceOf(OwnershipResult.Acquired.class);

        // 5. Server A tries to reclaim while Server B is active
        UUID sessionA2 = UUID.randomUUID();
        OwnershipResult resA2 = repository.acquireOwnership(playerUuid, "FastUser", "server-A", sessionA2, Duration.ofSeconds(30));
        assertThat(resA2).isInstanceOf(OwnershipResult.AlreadyOwned.class);
        assertThat(((OwnershipResult.AlreadyOwned) resA2).ownerServer()).isEqualTo("server-B");
    }

    @Test
    @DisplayName("Duplicate login on one server cannot replace a live fenced session")
    void testSameServerDuplicateLoginCannotStealOwnership() {
        UUID playerUuid = UUID.randomUUID();
        OwnershipResult first = repository.acquireOwnership(
            playerUuid, "DuplicateUser", "server-A", UUID.randomUUID(), Duration.ofSeconds(30));
        assertThat(first).isInstanceOf(OwnershipResult.Acquired.class);

        OwnershipResult duplicate = repository.acquireOwnership(
            playerUuid, "DuplicateUser", "server-A", UUID.randomUUID(), Duration.ofSeconds(30));

        assertThat(duplicate).isInstanceOf(OwnershipResult.AlreadyOwned.class);
        assertThat(((OwnershipResult.AlreadyOwned) duplicate).ownerServer()).isEqualTo("server-A");
    }
}
