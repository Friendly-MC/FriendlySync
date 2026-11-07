package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.SessionRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.messaging.message.TransferRequestMessage;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferHandoffRegressionTest {
    private final UUID uuid = UUID.randomUUID();
    private PlayerStateRepository repository;
    private DatabaseManager database;
    private MessagingService messaging;
    private PlayerStateService state;
    private PlatformScheduler scheduler;
    private FriendlySyncConfig config;

    @BeforeEach
    void setup() throws Exception {
        String jdbc = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE friendlysync_players (
                    uuid VARCHAR(36) PRIMARY KEY, username VARCHAR(16) NOT NULL,
                    revision BIGINT DEFAULT 0 NOT NULL, fencing_token BIGINT DEFAULT 0 NOT NULL,
                    active_session_id VARCHAR(36), owner_server VARCHAR(64),
                    session_state VARCHAR(32) DEFAULT 'OFFLINE' NOT NULL, lease_expires_at TIMESTAMP,
                    payload LONGBLOB, payload_version INT DEFAULT 1 NOT NULL, payload_hash VARCHAR(64),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """);
        }
        database = mock(DatabaseManager.class);
        when(database.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(jdbc));
        repository = spy(new PlayerStateRepository(database));
        messaging = mock(MessagingService.class);
        state = mock(PlayerStateService.class);
        when(state.loadState(uuid)).thenReturn(CompletableFuture.completedFuture(new LoadResult.NotFound(0)));
        scheduler = mock(PlatformScheduler.class);
        doAnswer(invocation -> { invocation.getArgument(0, Runnable.class).run(); return null; })
            .when(scheduler).runAsync(any(Runnable.class));
    }

    private PlayerSessionCoordinator coordinator(String server) {
        config = mock(FriendlySyncConfig.class);
        when(config.getServerId()).thenReturn(server);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        when(config.getTransferTimeout()).thenReturn(Duration.ofSeconds(2));
        when(config.getRecoveryMaximumWait()).thenReturn(Duration.ofSeconds(45));
        PlayerSessionService sessions = new PlayerSessionService();
        PlayerOwnershipService ownership = new PlayerOwnershipService(config, repository,
            mock(SessionRepository.class), sessions);
        return new PlayerSessionCoordinator(ownership, sessions, state, mock(SyncModuleManager.class),
            new JoinLockService(sessions), scheduler, null, messaging, null);
    }

    private OwnershipResult.Acquired acquire(String server) {
        return (OwnershipResult.Acquired) repository.acquireOwnership(uuid, "ReviewPlayer", server,
            UUID.randomUUID(), Duration.ofSeconds(45));
    }

    private AsyncPlayerPreLoginEvent login() {
        AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getUniqueId()).thenReturn(uuid);
        when(event.getName()).thenReturn("ReviewPlayer");
        return event;
    }

    @Test
    void abandonedPreLoginRemainsLoadingAndCanBeAcquiredAfterLeaseExpiry() throws Exception {
        var source = coordinator("spawn-a");
        var event = login();
        source.handleAsyncPreLogin(event);
        verify(event, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(Component.class));
        var pending = repository.find(uuid).orElseThrow();
        assertThat(pending.sessionState()).isEqualTo("LOADING");
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(
            "UPDATE friendlysync_players SET lease_expires_at = DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
        var recovered = acquire("economy-a");
        assertThat(recovered.session().fencingToken()).isGreaterThan(pending.fencingToken());
        assertThat(repository.updateSessionState(uuid, pending.activeSessionId(), pending.fencingToken(), "ACTIVE")).isFalse();
    }

    @Test
    void returningSourceReacquiresFreshFenceAndPreservesCommittedTransferSnapshot() throws Exception {
        var old = acquire("spawn-a").session();
        byte[] inventory = new byte[]{11, 22, 33};
        assertThat(repository.commit(uuid, old.sessionId(), old.fencingToken(), 0,
            inventory, 1, "inventory-hash")).isInstanceOf(CommitResult.Success.class);
        assertThat(repository.releaseOwnership(uuid, old.sessionId(), old.fencingToken(), "RELEASED")).isTrue();
        var returned = acquire("spawn-a").session();
        assertThat(returned.sessionId()).isNotEqualTo(old.sessionId());
        assertThat(returned.fencingToken()).isGreaterThan(old.fencingToken());
        assertThat(repository.find(uuid).orElseThrow().payload()).containsExactly(inventory);
        assertThat(repository.updateSessionState(uuid, old.sessionId(), old.fencingToken(), "ACTIVE")).isFalse();
        assertThat(repository.commit(uuid, old.sessionId(), old.fencingToken(), 1,
            new byte[]{99}, 1, "stale")).isNotInstanceOf(CommitResult.Success.class);
        assertThat(repository.find(uuid).orElseThrow().payload()).containsExactly(inventory);
        assertThat(repository.updateSessionState(uuid, returned.sessionId(), returned.fencingToken(), "ACTIVE")).isTrue();
    }

    @Test
    void lateActivationCannotChangeReleasedSessionBackToActive() throws Exception {
        var source = acquire("spawn-a");
        assertThat(repository.releaseOwnership(uuid, source.session().sessionId(), source.session().fencingToken(), "OFFLINE")).isTrue();
        assertThat(repository.updateSessionState(uuid, source.session().sessionId(), source.session().fencingToken(), "ACTIVE")).isFalse();
        assertThat(repository.find(uuid).orElseThrow().sessionState()).isEqualTo("OFFLINE");
    }

    @Test
    void missingReadyNotificationAcquiresReleasedSqlRowPromptlyWithoutDownloadingInventory() throws Exception {
        var source = acquire("spawn-a");
        doAnswer(invocation -> {
            assertThat(repository.releaseOwnership(uuid, source.session().sessionId(),
                source.session().fencingToken(), "RELEASED")).isTrue();
            // The durable release succeeded, but the best-effort Redis ready message is lost.
            return null;
        }).when(messaging).publishTransferRequest(any());
        var target = coordinator("economy-a");
        var event = login();
        long started = System.nanoTime();
        target.handleAsyncPreLogin(event);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1000);
        verify(event, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(Component.class));
        verify(repository, times(2)).acquireOwnership(eq(uuid), eq("ReviewPlayer"), eq("economy-a"), any(), any());
        verify(repository, never()).find(any());
        assertThat(repository.find(uuid).orElseThrow().ownerServer()).isEqualTo("economy-a");
    }

    @Test
    void expiredActiveSourceIsStillRejectedAndCannotBeRecoveredByNormalLogin() throws Exception {
        var source = acquire("spawn-a");
        assertThat(repository.updateSessionState(uuid, source.session().sessionId(),
            source.session().fencingToken(), "ACTIVE")).isTrue();
        // Set only the lease into the past; retain the live-session state, as after a crash.
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(
            "UPDATE friendlysync_players SET lease_expires_at = DATEADD('SECOND', -60, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
        var event = login();
        coordinator("economy-a").handleAsyncPreLogin(event);
        var message = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), message.capture());
        assertThat(message.getValue().toString()).contains("needs recovery");
        assertThat(repository.find(uuid).orElseThrow().ownerServer()).isEqualTo("spawn-a");
        verify(state, never()).loadState(any());
    }

    @Test
    void delayedRequestToPreviousSourcePreservesNewEconomyOwnershipAndItsSaves() throws Exception {
        var economy = acquire("economy-a");
        assertThat(repository.updateSessionState(uuid, economy.session().sessionId(),
            economy.session().fencingToken(), "ACTIVE")).isTrue();
        var previousSource = coordinator("spawn-a");
        previousSource.handleIncomingTransferRequest(TransferRequestMessage.create(uuid, "economy-a", "spawn-a"));
        var current = repository.find(uuid).orElseThrow();
        assertThat(current.ownerServer()).isEqualTo("economy-a");
        assertThat(current.sessionState()).isEqualTo("ACTIVE");
        assertThat(current.activeSessionId()).isEqualTo(economy.session().sessionId());
        verify(messaging, never()).publishTransferReady(any());
        assertThat(repository.commit(uuid, economy.session().sessionId(), economy.session().fencingToken(),
            0, new byte[] {1}, 1, "hash")).isInstanceOf(CommitResult.Success.class);
    }

    @Test
    void localMissingSessionCannotForceReleaseAnActiveSqlOwner() throws Exception {
        var source = acquire("spawn-a");
        repository.updateSessionState(uuid, source.session().sessionId(), source.session().fencingToken(), "ACTIVE");
        var target = coordinator("spawn-a");
        when(config.getTransferTimeout()).thenReturn(Duration.ofMillis(50));
        var event = login();
        target.handleAsyncPreLogin(event);
        assertThat(repository.find(uuid).orElseThrow().ownerServer()).isEqualTo("spawn-a");
        verify(repository, never()).forceReleaseOwnership(any(), any());
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
    }

    @Test
    void blockedHandoffUsesBackedOffMetadataReadsInsteadOfRepeatedWriteTransactions() throws Exception {
        acquire("spawn-a");
        var target = coordinator("economy-a");
        when(config.getTransferTimeout()).thenReturn(Duration.ofMillis(750));
        target.handleAsyncPreLogin(login());
        verify(repository, times(2)).acquireOwnership(eq(uuid), eq("ReviewPlayer"), eq("economy-a"), any(), any());
        verify(repository, atLeast(1)).findMetadata(uuid);
        verify(repository, atMost(5)).findMetadata(uuid);
        verify(repository, never()).find(any());
    }

    @Test
    void throwingRedisNotificationDoesNotPreventSqlHandoffFallback() throws Exception {
        var source = acquire("spawn-a");
        doAnswer(invocation -> {
            repository.releaseOwnership(uuid, source.session().sessionId(), source.session().fencingToken(), "RELEASED");
            throw new IllegalStateException("Redis unavailable");
        }).when(messaging).publishTransferRequest(any());
        var event = login();
        coordinator("economy-a").handleAsyncPreLogin(event);
        verify(event, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(Component.class));
        assertThat(repository.find(uuid).orElseThrow().ownerServer()).isEqualTo("economy-a");
    }

    @Test
    void rejectingDuplicateLoginPreservesLiveSessionAndFinalSave() {
        var sessions = new PlayerSessionService();
        var session = new dev.lyy.friendlysync.model.ActivePlayerSession(uuid, UUID.randomUUID(),
            "spawn-a", 1, 0, "ReviewPlayer", SessionState.ACTIVE);
        sessions.registerSession(session);
        sessions.markPlayerOnline(uuid);
        var ownership = mock(PlayerOwnershipService.class);
        var target = new PlayerSessionCoordinator(ownership, sessions, state, mock(SyncModuleManager.class),
            new JoinLockService(sessions), scheduler, null);
        target.handleLoginDisallowed(uuid);
        assertThat(sessions.isPlayerOnline(uuid)).isTrue();
        assertThat(sessions.getLocalSession(uuid)).contains(session);
        sessions.markPlayerOffline(uuid);
        session.forceState(SessionState.SAVING);
        target.handleLoginDisallowed(uuid);
        assertThat(sessions.getLocalSession(uuid)).contains(session);
        verify(ownership, never()).releaseOwnership(any(), any());
    }

    @Test
    void stateLoadUsesRemainingLoginBudgetAfterSlowAcquisition() throws Exception {
        var sessions = new PlayerSessionService();
        var ownership = mock(PlayerOwnershipService.class);
        var identity = new dev.lyy.friendlysync.model.SessionIdentity(uuid, UUID.randomUUID(), "economy-a", 1);
        when(ownership.acquireOwnership(uuid, "ReviewPlayer")).thenAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(3);
            return new OwnershipResult.Acquired(identity, 0);
        });
        when(state.loadState(uuid)).thenReturn(new CompletableFuture<>());
        var target = new PlayerSessionCoordinator(ownership, sessions, state, mock(SyncModuleManager.class),
            new JoinLockService(sessions), scheduler, null);
        var event = login();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(17), () -> target.handleAsyncPreLogin(event));
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
        verify(ownership).releaseOwnership(identity, "FAILED");
    }
}
