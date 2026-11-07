package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.SessionRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.messaging.message.TransferReadyMessage;
import dev.lyy.friendlysync.messaging.message.TransferRequestMessage;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OrphanedSessionRecoveryTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;
    private SessionRepository sessionRepository;
    private FriendlySyncConfig config;
    private Set<UUID> onlinePlayers;

    private static final String JDBC_URL = "jdbc:h2:mem:orphaned_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

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
        sessionRepository = Mockito.mock(SessionRepository.class);

        config = Mockito.mock(FriendlySyncConfig.class);
        Mockito.when(config.getServerId()).thenReturn("economy-b");
        Mockito.when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        Mockito.when(config.getSessionHeartbeatInterval()).thenReturn(Duration.ofSeconds(10));

        onlinePlayers = ConcurrentHashMap.newKeySet();
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
    void longRunningOfflineFinalSaveIsNotReleasedByHeartbeat() throws Exception {
        PlayerSessionService sessions = new PlayerSessionService();
        PlayerOwnershipService ownership = new PlayerOwnershipService(
            config, repository, sessionRepository, sessions, null, onlinePlayers::contains);
        UUID uuid = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        repository.acquireOwnership(uuid, "SlowSave", "economy-b", id, Duration.ofSeconds(45));
        sessions.registerSession(new ActivePlayerSession(uuid, id, "economy-b", 1, 0,
            "SlowSave", SessionState.SAVING, Instant.now().minusSeconds(120)));
        ownership.renewActiveLeases();
        assertThat(sessions.getLocalSession(uuid)).isPresent();
        assertThat(repository.find(uuid).orElseThrow().ownerServer()).isEqualTo("economy-b");
    }

    @Test
    @DisplayName("Orphaned ACQUIRING session (>30s) with offline player is evicted and lease is NOT renewed")
    void testOrphanedAcquiringSessionEvicted() throws Exception {
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerOwnershipService ownershipService = new PlayerOwnershipService(
            config, repository, sessionRepository, sessionService, null,
            onlinePlayers::contains
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        // 1. Acquire ownership in DB
        OwnershipResult acq = repository.acquireOwnership(
            playerUuid, "TestPlayer", "economy-b", sessionId, Duration.ofSeconds(45)
        );
        assertThat(acq).isInstanceOf(OwnershipResult.Acquired.class);

        // 2. Register session in memory with createdAt = 40 seconds ago (exceeds 30s timeout)
        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "economy-b", 1L, 0L, "TestPlayer",
            SessionState.ACQUIRING, Instant.now().minusSeconds(40)
        );
        sessionService.registerSession(session);


        // Player is offline (not in onlinePlayers set)
        assertThat(sessionService.getLocalSession(playerUuid)).isPresent();

        // 3. Trigger renewActiveLeases
        ownershipService.renewActiveLeases();

        // 4. Session MUST be evicted from memory
        assertThat(sessionService.getLocalSession(playerUuid)).isEmpty();

        // 5. Database row MUST be released to OFFLINE
        var persistedOpt = repository.find(playerUuid);
        assertThat(persistedOpt).isPresent();
        assertThat(persistedOpt.get().ownerServer()).isNull();
        assertThat(persistedOpt.get().sessionState()).isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("Fresh ACQUIRING session (<30s) within handshake window is NOT evicted prematurely")
    void testFreshAcquiringSessionPreserved() throws Exception {
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerOwnershipService ownershipService = new PlayerOwnershipService(
            config, repository, sessionRepository, sessionService, null,
            onlinePlayers::contains
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "FreshPlayer", "economy-b", sessionId, Duration.ofSeconds(45));

        // Created 5 seconds ago
        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "economy-b", 1L, 0L, "FreshPlayer",
            SessionState.ACQUIRING, Instant.now().minusSeconds(5)
        );
        sessionService.registerSession(session.toIdentity(), 0L, "FreshPlayer", SessionState.ACQUIRING);

        ownershipService.renewActiveLeases();

        // Session must still be present in memory during valid handshake window
        assertThat(sessionService.getLocalSession(playerUuid)).isPresent();
    }

    @Test
    @DisplayName("ACTIVE offline session retains ownership until a final save is confirmed")
    void testActiveSessionWithOfflinePlayerEvicted() throws Exception {
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerOwnershipService ownershipService = new PlayerOwnershipService(
            config, repository, sessionRepository, sessionService, null,
            onlinePlayers::contains
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "ActivePlayer", "economy-b", sessionId, Duration.ofSeconds(45));

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "economy-b", 1L, 0L, "ActivePlayer",
            SessionState.ACTIVE, Instant.now().minusSeconds(10)
        );
        sessionService.registerSession(session.toIdentity(), 0L, "ActivePlayer", SessionState.ACTIVE);

        // Player is offline
        ownershipService.renewActiveLeases();

        // No final snapshot was saved; do not silently permit a stale login.
        assertThat(sessionService.getLocalSession(playerUuid)).isPresent();
        var persisted = repository.find(playerUuid).orElseThrow();
        assertThat(persisted.ownerServer()).isEqualTo("economy-b");
    }

    @Test
    @DisplayName("Incoming TransferRequest immediately releases orphaned session and notifies destination")
    void testIncomingTransferRequestReleasesOrphanedSession() throws Exception {

        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerOwnershipService ownershipService = new PlayerOwnershipService(
            config, repository, sessionRepository, sessionService, null,
            onlinePlayers::contains
        );

        MessagingService messagingService = Mockito.mock(MessagingService.class);
        PlayerStateService stateService = Mockito.mock(PlayerStateService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        JoinLockService joinLockService = new JoinLockService(sessionService);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        PlayerSessionCoordinator coordinator = new PlayerSessionCoordinator(
            ownershipService, sessionService, stateService, syncModuleManager,
            joinLockService, scheduler, null, messagingService, null
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "HandoffPlayer", "economy-b", sessionId, Duration.ofSeconds(45));

        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "economy-b", 1L, 0L, "HandoffPlayer",
            SessionState.ACQUIRING, Instant.now().minusSeconds(15)
        );
        sessionService.registerSession(session);


        // Destination server 'spawn-eco' sends TransferRequestMessage
        TransferRequestMessage request = TransferRequestMessage.create(playerUuid, "spawn-eco");
        coordinator.handleIncomingTransferRequest(request);

        ArgumentCaptor<Runnable> worker = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runAsync(worker.capture());
        assertThat(sessionService.getLocalSession(playerUuid)).isPresent();
        worker.getValue().run();

        // 1. Session must be released from memory
        assertThat(sessionService.getLocalSession(playerUuid)).isEmpty();

        // 2. Ownership in DB must be released to OFFLINE
        var persisted = repository.find(playerUuid).orElseThrow();
        assertThat(persisted.ownerServer()).isNull();
        assertThat(persisted.sessionState()).isEqualTo("OFFLINE");

        // 3. Must publish TransferReadyMessage to Redis
        ArgumentCaptor<TransferReadyMessage> captor = ArgumentCaptor.forClass(TransferReadyMessage.class);
        verify(messagingService).publishTransferReady(captor.capture());
        assertThat(captor.getValue().playerUuid()).isEqualTo(playerUuid);
        assertThat(captor.getValue().targetServer()).isEqualTo("spawn-eco");

    }

    @Test
    @DisplayName("PlayerStateRepository: releaseOwnershipIfOwner requires matching owner and expired lease")
    void testReleaseOwnershipIfOwner() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "UserA", "economy-b", sessionId, Duration.ofSeconds(45));

        // Wrong server cannot release
        boolean releasedWrong = repository.releaseOwnershipIfOwner(playerUuid, "other-server", "OFFLINE");
        assertThat(releasedWrong).isFalse();

        var stateAfterWrong = repository.find(playerUuid).orElseThrow();
        assertThat(stateAfterWrong.ownerServer()).isEqualTo("economy-b");

        // Even the matching server ID cannot release a live lease without the exact session identity.
        assertThat(repository.releaseOwnershipIfOwner(playerUuid, "economy-b", "OFFLINE")).isFalse();

        try (Connection conn = mockDatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                 "UPDATE friendlysync_players SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }

        // Once the lease is expired, the matching owner can clean up the orphan.
        boolean releasedCorrect = repository.releaseOwnershipIfOwner(playerUuid, "economy-b", "OFFLINE");
        assertThat(releasedCorrect).isTrue();

        var stateAfterCorrect = repository.find(playerUuid).orElseThrow();
        assertThat(stateAfterCorrect.ownerServer()).isNull();
        assertThat(stateAfterCorrect.sessionState()).isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("PlayerStateRepository: forceReleaseOwnership unconditionally resets ownership")
    void testForceReleaseOwnership() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "StuckUser", "any-server", sessionId, Duration.ofSeconds(45));

        boolean forced = repository.forceReleaseOwnership(playerUuid, "OFFLINE");
        assertThat(forced).isTrue();

        var state = repository.find(playerUuid).orElseThrow();
        assertThat(state.ownerServer()).isNull();
        assertThat(state.sessionState()).isEqualTo("OFFLINE");
    }

    @Test
    void administrativeUnlockProtectsFreshLeasesAndFencesExpiredWriters() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var acquired = (dev.lyy.friendlysync.model.result.OwnershipResult.Acquired)
            repository.acquireOwnership(uuid, "StuckUser", "remote", sessionId, Duration.ofSeconds(45));
        assertThat(repository.unlockExpiredOwnership(uuid)).isFalse();
        try (var connection = mockDatabaseManager.getConnection();
             var statement = connection.prepareStatement(
                 "UPDATE friendlysync_players SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
        assertThat(repository.unlockExpiredOwnership(uuid)).isTrue();
        var row = repository.find(uuid).orElseThrow();
        assertThat(row.ownerServer()).isNull();
        assertThat(row.activeSessionId()).isNull();
        assertThat(row.fencingToken()).isEqualTo(acquired.session().fencingToken() + 1);
        assertThat(repository.commit(uuid, sessionId, acquired.session().fencingToken(), 0, new byte[]{1}, 3, "hash"))
            .isInstanceOf(dev.lyy.friendlysync.model.result.CommitResult.Conflict.class);
        assertThat(repository.unlockExpiredOwnership(uuid)).isFalse();
    }

    @Test
    @DisplayName("PlayerStateRepository: findByUsername looks up player case-insensitively")
    void testFindByUsername() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "NarutoLunakai", "economy-b", sessionId, Duration.ofSeconds(45));

        Optional<dev.lyy.friendlysync.database.repository.PersistedPlayerState> foundExact = repository.findByUsername("NarutoLunakai");
        assertThat(foundExact).isPresent();
        assertThat(foundExact.get().uuid()).isEqualTo(playerUuid);

        Optional<dev.lyy.friendlysync.database.repository.PersistedPlayerState> foundLower = repository.findByUsername("narutolunakai");
        assertThat(foundLower).isPresent();
        assertThat(foundLower.get().uuid()).isEqualTo(playerUuid);

        Optional<dev.lyy.friendlysync.database.repository.PersistedPlayerState> notFound = repository.findByUsername("NonExistent");
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("Fresh SAVING session with old createdAt (>30s) is NOT prematurely evicted by renewActiveLeases")
    void testFreshSavingSessionWithOldCreatedAtNotEvicted() throws Exception {
        PlayerSessionService sessionService = new PlayerSessionService();
        PlayerOwnershipService ownershipService = new PlayerOwnershipService(
            config, repository, sessionRepository, sessionService, null,
            onlinePlayers::contains
        );

        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        repository.acquireOwnership(playerUuid, "OldPlayerSaving", "economy-b", sessionId, Duration.ofSeconds(45));

        // Created 5 minutes ago (long-lived player session)
        ActivePlayerSession session = new ActivePlayerSession(
            playerUuid, sessionId, "economy-b", 1L, 0L, "OldPlayerSaving",
            SessionState.ACTIVE, Instant.now().minusSeconds(300)
        );
        sessionService.registerSession(session);

        // Player quits and session transitions to SAVING right now
        session.transitionTo(SessionState.SAVING);

        // onlinePlayers does NOT contain player (marked offline on quit)
        ownershipService.renewActiveLeases();

        // The session just entered SAVING seconds ago, so it must NOT be evicted
        assertThat(sessionService.getLocalSession(playerUuid)).isPresent();
        var persisted = repository.find(playerUuid).orElseThrow();
        assertThat(persisted.ownerServer()).isEqualTo("economy-b");
    }
}
