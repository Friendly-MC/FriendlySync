package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRepository;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.session.FinalSaveJournal;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerSnapshotRepositoryTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerSnapshotRepository snapshotRepository;
    private PlayerStateRepository stateRepository;

    private static final String JDBC_URL = "jdbc:h2:mem:snapshot_repo_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @Test
    void offlineRestoreBacksUpCurrentDataAndAdvancesRevisionAndFence() throws Exception {
        UUID uuid = UUID.randomUUID();
        var before = offlineProfile(uuid);
        byte[] restored = "restored".getBytes(StandardCharsets.UTF_8);

        CommitResult result = stateRepository.restoreSnapshot(before, restored, 3, DigestUtils.sha256(restored));

        assertThat(result).isInstanceOf(CommitResult.Success.class);
        var after = stateRepository.find(uuid).orElseThrow();
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.fencingToken()).isEqualTo(before.fencingToken() + 1);
        assertThat(after.ownerServer()).isNull();
        assertThat(after.payload()).isEqualTo(restored);
        var history = snapshotRepository.findRecentSnapshots(uuid, 10);
        assertThat(history).anySatisfy(record -> {
            assertThat(record.syncReason()).isEqualTo("ADMIN_RESTORE_BACKUP");
            assertThat(record.payload()).isEqualTo(before.payload());
        }).anySatisfy(record -> {
            assertThat(record.syncReason()).isEqualTo("ADMIN_RESTORE");
            assertThat(record.payload()).isEqualTo(restored);
        });
        assertThat(stateRepository.commit(uuid, before.activeSessionId(), before.fencingToken(), before.revision(),
            before.payload(), 3, before.payloadHash())).isInstanceOf(CommitResult.Conflict.class);
    }

    @Test
    void restoreCannotOverwriteAConcurrentDestinationOwner() throws Exception {
        UUID uuid = UUID.randomUUID();
        var before = offlineProfile(uuid);
        var acquired = stateRepository.acquireOwnership(uuid, "Player", "destination", UUID.randomUUID(), Duration.ofMinutes(1));
        assertThat(acquired).isInstanceOf(OwnershipResult.Acquired.class);

        assertThat(stateRepository.restoreSnapshot(before, new byte[] {2}, 3, DigestUtils.sha256(new byte[] {2})))
            .isInstanceOf(CommitResult.Conflict.class);

        var after = stateRepository.find(uuid).orElseThrow();
        assertThat(after.payload()).isEqualTo(before.payload());
        assertThat(after.ownerServer()).isEqualTo("destination");
        assertThat(snapshotRepository.findRecentSnapshots(uuid, 10)).isEmpty();
    }

    @Test
    void restoreRollsBackPayloadRevisionAndFenceWhenBackupStorageFails() throws Exception {
        UUID uuid = UUID.randomUUID();
        var before = offlineProfile(uuid);
        try (var statement = rootConnection.createStatement()) {
            statement.execute("DROP TABLE friendlysync_snapshots");
        }

        assertThat(stateRepository.restoreSnapshot(before, new byte[] {2}, 3, DigestUtils.sha256(new byte[] {2})))
            .isInstanceOf(CommitResult.Failed.class);
        assertThat(stateRepository.find(uuid)).contains(before);
    }

    @Test
    void restoreRejectsBothForeignHistoryAndForeignPayloadIdentity() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var before = offlineProfile(uuid);
        var serializer = new dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer(
            new dev.lyy.friendlysync.serialization.CompressionService(
                dev.lyy.friendlysync.serialization.CompressionAlgorithm.NONE, 0));
        var service = new PlayerStateService(stateRepository, serializer, new PlayerOperationQueue(Runnable::run),
            Mockito.mock(SyncModuleManager.class), new PlayerSessionService(), Mockito.mock(PlatformScheduler.class));
        for (boolean foreignRecord : new boolean[] {true, false}) {
            String id = UUID.randomUUID().toString();
            byte[] payload = serializer.serialize(profile(other, 9));
            snapshotRepository.insertSnapshot(new PlayerSnapshotRecord(id, foreignRecord ? other : uuid,
                1, 1, UUID.randomUUID(), "source", "QUIT", "COMMITTED", payload, 3, DigestUtils.sha256(payload), Instant.now()));

            assertThat(service.restoreSnapshot(uuid, id).get(3, TimeUnit.SECONDS)).contains("different player");
            assertThat(stateRepository.find(uuid)).contains(before);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false,false", "true,false,false", "false,true,false", "false,true,true"})
    void onlineRestoreSavesLatestDataBeforeReplacementAndUsesOnlyWorkerSql(
        boolean restoreFails, boolean disconnectDuringSave, boolean originalSaveFails, @TempDir Path journal
    ) throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(uuid, "Player", "source", sessionId, Duration.ofMinutes(1));
        var serializer = new dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer(
            new dev.lyy.friendlysync.serialization.CompressionService(
                dev.lyy.friendlysync.serialization.CompressionAlgorithm.NONE, 0));
        byte[] initial = serializer.serialize(profile(uuid, 3));
        stateRepository.commit(uuid, sessionId, acquired.session().fencingToken(), 0, initial, 3, DigestUtils.sha256(initial));
        String id = UUID.randomUUID().toString();
        byte[] old = serializer.serialize(profile(uuid, 5));
        snapshotRepository.insertSnapshot(new PlayerSnapshotRecord(id, uuid, 0, 1, sessionId,
            "source", "QUIT", "COMMITTED", old, 3, DigestUtils.sha256(old), Instant.now()));
        var sessions = new PlayerSessionService();
        var session = new ActivePlayerSession(uuid, sessionId, "source", acquired.session().fencingToken(), 1, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        sessions.markPlayerOnline(uuid);
        var entityThread = new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.when(mockDatabaseManager.getConnection()).thenAnswer(call -> {
            assertThat(entityThread.get()).as("JDBC must stay off the player thread").isFalse();
            return DriverManager.getConnection(JDBC_URL, "sa", "");
        });
        var workers = new java.util.ArrayDeque<Runnable>();
        var executor = Mockito.mock(java.util.concurrent.ExecutorService.class);
        Mockito.doAnswer(call -> { workers.add(call.getArgument(0)); return null; }).when(executor).execute(Mockito.any());
        var scheduler = Mockito.mock(PlatformScheduler.class);
        Mockito.doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(Mockito.any());
        var player = Mockito.mock(org.bukkit.entity.Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.isOnline()).thenReturn(true);
        Mockito.doAnswer(call -> {
            entityThread.set(true);
            try { call.getArgument(1, Runnable.class).run(); } finally { entityThread.set(false); }
            return null;
        }).when(scheduler).runForPlayer(Mockito.eq(player), Mockito.any(), Mockito.any());
        Mockito.doAnswer(call -> { if (player.isOnline()) call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runForPlayer(Mockito.eq(player), Mockito.any());
        var modules = Mockito.mock(SyncModuleManager.class);
        Mockito.when(modules.captureSnapshot(Mockito.eq(player), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.eq(session)))
            .thenAnswer(call -> {
                assertThat(entityThread.get()).isTrue();
                if (disconnectDuringSave) {
                    sessions.markPlayerOffline(uuid);
                    Mockito.when(player.isOnline()).thenReturn(false);
                }
                return profile(uuid, 42);
            });
        var repository = Mockito.spy(stateRepository);
        if (restoreFails) {
            Mockito.doReturn(new CommitResult.Failed(new java.sql.SQLException("restore unavailable")))
                .when(repository).restoreSnapshot(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
        }
        if (originalSaveFails) {
            Mockito.doReturn(new CommitResult.Failed(new java.sql.SQLException("final save unavailable")))
                .when(repository).commit(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.any(), Mockito.any());
        }
        var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(executor),
            modules, sessions, scheduler, executor, null);
        service.enableFinalSaveJournal(journal, "source");
        try (var bukkit = Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(uuid)).thenReturn(player);
            var result = service.restoreSnapshot(uuid, id);
            assertThat(result).isNotDone();
            assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);
            int tasks = 0;
            while (!workers.isEmpty()) {
                assertThat(++tasks).isLessThan(20);
                workers.remove().run();
            }
            assertThat(result.get(3, TimeUnit.SECONDS)).contains(originalSaveFails ? "Restore refused"
                : restoreFails ? "conflict or failure" : "Restored snapshot");
        }
        if (originalSaveFails) {
            assertThat(service.hasPendingFinalSave(uuid)).isTrue();
            assertThat(sessions.getLocalSession(uuid)).isEmpty();
            Mockito.verify(repository, Mockito.never()).restoreSnapshot(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
            Mockito.doCallRealMethod().when(repository).commit(Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.anyLong(),
                Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.any(), Mockito.any());
            var recovered = service.recoverPendingFinalSaves();
            while (!workers.isEmpty()) workers.remove().run();
            recovered.get(3, TimeUnit.SECONDS);
        }
        var row = stateRepository.find(uuid).orElseThrow();
        assertThat(serializer.deserialize(row.payload()).experience().level()).isEqualTo(restoreFails || originalSaveFails ? 42 : 5);
        assertThat(row.ownerServer()).isNull();
        assertThat(sessions.getLocalSession(uuid)).isEmpty();
        assertThat(service.hasPendingFinalSave(uuid)).isFalse();
        Mockito.verify(player, Mockito.times(disconnectDuringSave ? 0 : 1)).kick(Mockito.any(net.kyori.adventure.text.Component.class));
        Mockito.verify(modules, Mockito.never()).applySnapshot(Mockito.any(), Mockito.any(), Mockito.any(ActivePlayerSession.class));
        if (!restoreFails && !originalSaveFails) {
            assertThat(snapshotRepository.findRecentSnapshots(uuid, 10)).anySatisfy(record -> {
                assertThat(record.syncReason()).isEqualTo("ADMIN_RESTORE_BACKUP");
                assertThat(serializer.deserialize(record.payload()).experience().level()).isEqualTo(42);
            });
        }
    }

    private dev.lyy.friendlysync.database.repository.PersistedPlayerState offlineProfile(UUID uuid) throws Exception {
        UUID session = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(uuid, "Player", "source", session, Duration.ofMinutes(1));
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        assertThat(stateRepository.commit(uuid, session, acquired.session().fencingToken(), 0, original, 3, DigestUtils.sha256(original)))
            .isInstanceOf(CommitResult.Success.class);
        assertThat(stateRepository.releaseOwnership(uuid, session, acquired.session().fencingToken(), "OFFLINE")).isTrue();
        return stateRepository.find(uuid).orElseThrow();
    }

    @Test
    void shutdownUnblocksRestoreWaitingForPlayerTicksWithoutChangingData() throws Exception {
        UUID uuid = UUID.randomUUID();
        offlineProfile(uuid);
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(uuid, "Player", "source", UUID.randomUUID(), Duration.ofMinutes(1));
        var before = stateRepository.find(uuid).orElseThrow();
        var serializer = new dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer(
            new dev.lyy.friendlysync.serialization.CompressionService(
                dev.lyy.friendlysync.serialization.CompressionAlgorithm.NONE, 0));
        String id = UUID.randomUUID().toString();
        byte[] payload = serializer.serialize(profile(uuid, 5));
        snapshotRepository.insertSnapshot(new PlayerSnapshotRecord(id, uuid, 0, 1, UUID.randomUUID(),
            "source", "QUIT", "COMMITTED", payload, 3, DigestUtils.sha256(payload), Instant.now()));
        var sessions = new PlayerSessionService();
        var session = sessions.registerSession(acquired.session(), before.revision(), "Player", SessionState.ACTIVE);
        var deferred = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        var scheduler = Mockito.mock(PlatformScheduler.class);
        Mockito.doAnswer(call -> { deferred.set(call.getArgument(0)); return null; }).when(scheduler).runGlobal(Mockito.any());
        var worker = Mockito.mock(java.util.concurrent.ExecutorService.class);
        Mockito.doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(worker).execute(Mockito.any());
        var modules = Mockito.mock(SyncModuleManager.class);
        var service = new PlayerStateService(stateRepository, serializer, new PlayerOperationQueue(worker), modules,
            sessions, scheduler, worker, null);

        var result = service.restoreSnapshot(uuid, id);
        assertThat(result).isNotDone();
        service.beginShutdown();
        assertThat(result.get(3, TimeUnit.SECONDS)).contains("shutting down");
        deferred.get().run();

        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);
        assertThat(stateRepository.find(uuid)).contains(before);
        Mockito.verify(modules, Mockito.never()).captureSnapshot(Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any(), Mockito.any());
    }

    private PlayerStateSnapshot profile(UUID uuid, int level) {
        return new PlayerStateSnapshot(uuid, 0, 1, null, null,
            new dev.lyy.friendlysync.model.ExperienceSnapshot(level, 0, level), null, null, List.of(), null, null,
            null, java.util.Map.of(), Instant.now(), 3);
    }

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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS friendlysync_snapshots (
                    snapshot_id VARCHAR(36) NOT NULL PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    revision BIGINT NOT NULL,
                    fencing_token BIGINT NOT NULL,
                    session_id VARCHAR(36) NOT NULL,
                    server_id VARCHAR(64) NOT NULL,
                    sync_reason VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',
                    payload LONGBLOB NOT NULL,
                    payload_version INT NOT NULL DEFAULT 1,
                    payload_hash VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """);
        }

        mockDatabaseManager = Mockito.mock(DatabaseManager.class);
        Mockito.doAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", "")).when(mockDatabaseManager).getConnection();
        snapshotRepository = new PlayerSnapshotRepository(mockDatabaseManager);
        stateRepository = new PlayerStateRepository(mockDatabaseManager, snapshotRepository);
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
    @DisplayName("Snapshots: insert, query latest, and query history from MySQL")
    void testInsertAndQuerySnapshots() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        byte[] payload1 = "inventory-rev-1".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "inventory-rev-2".getBytes(StandardCharsets.UTF_8);

        PlayerSnapshotRecord record1 = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            1L,
            10L,
            sessionId,
            "survival-1",
            "CHECKPOINT",
            "COMMITTED",
            payload1,
            1,
            DigestUtils.sha256(payload1),
            Instant.now().minusSeconds(10)
        );

        PlayerSnapshotRecord record2 = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            2L,
            10L,
            sessionId,
            "survival-1",
            "QUIT",
            "COMMITTED",
            payload2,
            1,
            DigestUtils.sha256(payload2),
            Instant.now()
        );

        snapshotRepository.insertSnapshot(record1);
        snapshotRepository.insertSnapshot(record2);

        Optional<PlayerSnapshotRecord> latestOpt = snapshotRepository.findLatestSnapshot(playerUuid);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().revision()).isEqualTo(2L);
        assertThat(latestOpt.get().payload()).isEqualTo(payload2);
        assertThat(latestOpt.get().syncReason()).isEqualTo("QUIT");

        List<PlayerSnapshotRecord> recent = snapshotRepository.findRecentSnapshots(playerUuid, 10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).revision()).isEqualTo(2L);
        assertThat(recent.get(1).revision()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Commit Pipeline: Commit saves snapshot to MySQL snapshots table on success")
    void testCommitPersistsSnapshotOnSuccess() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "TestPlayer", "server-a", sessionId, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionId, acquired.session().fencingToken(), "ACTIVE");

        byte[] payload = "player-diamond-armor-items".getBytes(StandardCharsets.UTF_8);
        String hash = DigestUtils.sha256(payload);

        PlayerSnapshotRepository delayedSnapshots = Mockito.spy(snapshotRepository);
        long[] snapshotDurationMs = {0};
        Mockito.doAnswer(invocation -> {
            long start = System.nanoTime();
            TimeUnit.MILLISECONDS.sleep(60);
            Object copied = invocation.callRealMethod();
            snapshotDurationMs[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return copied;
        }).when(delayedSnapshots).copyCommittedSnapshot(Mockito.any(Connection.class), Mockito.any(PlayerSnapshotRecord.class));
        stateRepository = new PlayerStateRepository(mockDatabaseManager, delayedSnapshots);

        CommitResult result = stateRepository.commit(
            playerUuid, sessionId, acquired.session().fencingToken(), 0L, payload, 1, hash, "QUIT", "server-a");

        assertThat(result).isInstanceOf(CommitResult.Success.class);
        assertThat(((CommitResult.Success) result).durationMs()).isGreaterThanOrEqualTo(snapshotDurationMs[0]);

        // Verify snapshot was automatically recorded in friendlysync_snapshots in MySQL
        Optional<PlayerSnapshotRecord> snapOpt = snapshotRepository.findLatestSnapshot(playerUuid);
        assertThat(snapOpt).isPresent();
        assertThat(snapOpt.get().revision()).isEqualTo(1L);
        assertThat(snapOpt.get().status()).isEqualTo("COMMITTED");
        assertThat(snapOpt.get().syncReason()).isEqualTo("QUIT");
        assertThat(snapOpt.get().serverId()).isEqualTo("server-a");
        assertThat(snapOpt.get().payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Conflict Protection: Superseded commit still preserves player items into MySQL snapshots")
    void testCommitPreservesDataInSnapshotsEvenOnConflict() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        var acquiredA = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "ConflictPlayer", "server-a", sessionA, Duration.ofSeconds(45));

        // Server B supersedes Server A with higher fencing token
        stateRepository.releaseOwnership(playerUuid, sessionA, acquiredA.session().fencingToken(), "OFFLINE");
        UUID sessionB = UUID.randomUUID();
        var acquiredB = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "ConflictPlayer", "server-b", sessionB, Duration.ofSeconds(45));

        // Server A late commit (stale token & session)
        byte[] payloadA = "server-a-uncommitted-gear".getBytes(StandardCharsets.UTF_8);
        String hashA = DigestUtils.sha256(payloadA);

        CommitResult staleCommit = stateRepository.commit(
            playerUuid, sessionA, acquiredA.session().fencingToken(), 0L, payloadA, 1, hashA, "LATE_QUIT", "server-a");

        // The optimistic commit to friendlysync_players is rejected (CAS conflict)
        assertThat(staleCommit).isInstanceOf(CommitResult.Conflict.class);

        // BUT the snapshot data item IS stored in MySQL friendlysync_snapshots!
        List<PlayerSnapshotRecord> snapshots = snapshotRepository.findRecentSnapshots(playerUuid, 10);
        assertThat(snapshots).isNotEmpty();
        PlayerSnapshotRecord preserved = snapshots.get(0);
        assertThat(preserved.status()).isEqualTo("SUPERSEDED");
        assertThat(preserved.payload()).isEqualTo(payloadA);
        assertThat(preserved.syncReason()).isEqualTo("LATE_QUIT");
        assertThat(preserved.serverId()).isEqualTo("server-a");
    }

    @Test
    @DisplayName("Continuous Snapshot Suppression: Checkpoints do not insert snapshots into MySQL by default")
    void testCommitCheckpointSkipsSnapshotsByDefault() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "CheckpointPlayer", "server-a", sessionId, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionId, acquired.session().fencingToken(), "ACTIVE");

        byte[] payload = "player-checkpoint-items".getBytes(StandardCharsets.UTF_8);
        String hash = DigestUtils.sha256(payload);

        CommitResult result = stateRepository.commit(
            playerUuid, sessionId, acquired.session().fencingToken(), 0L, payload, 1, hash, "CHECKPOINT", "server-a");

        assertThat(result).isInstanceOf(CommitResult.Success.class);

        // Verify primary player row updated, but friendlysync_snapshots remains clean
        var updatedRow = stateRepository.find(playerUuid).orElseThrow();
        assertThat(updatedRow.revision()).isEqualTo(1L);
        Optional<PlayerSnapshotRecord> snapOpt = snapshotRepository.findLatestSnapshot(playerUuid);
        assertThat(snapOpt).isEmpty();
    }

    @Test
    @DisplayName("Continuous Snapshot Config: Enabling continuous snapshots persists even on CHECKPOINT")
    void testCommitCheckpointWithContinuousSnapshotsEnabled() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "ContinuousPlayer", "server-a", sessionId, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionId, acquired.session().fencingToken(), "ACTIVE");

        stateRepository.setContinuousSnapshotsEnabled(true);
        try {
            byte[] payload = "player-continuous-items".getBytes(StandardCharsets.UTF_8);
            String hash = DigestUtils.sha256(payload);

            CommitResult result = stateRepository.commit(
                playerUuid, sessionId, acquired.session().fencingToken(), 0L, payload, 1, hash, "CHECKPOINT", "server-a");

            assertThat(result).isInstanceOf(CommitResult.Success.class);
            Optional<PlayerSnapshotRecord> snapOpt = snapshotRepository.findLatestSnapshot(playerUuid);
            assertThat(snapOpt).isPresent();
            assertThat(snapOpt.get().syncReason()).isEqualTo("CHECKPOINT");
        } finally {
            stateRepository.setContinuousSnapshotsEnabled(false);
        }
    }

    @Test
    @DisplayName("Pruning: prunes snapshots older than retention period")
    void testPruneOldSnapshots() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);

        PlayerSnapshotRecord oldRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            1L,
            1L,
            UUID.randomUUID(),
            "server-a",
            "CHECKPOINT",
            "COMMITTED",
            payload,
            1,
            DigestUtils.sha256(payload),
            Instant.now().minus(Duration.ofDays(10))
        );

        PlayerSnapshotRecord newRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            2L,
            1L,
            UUID.randomUUID(),
            "server-a",
            "CHECKPOINT",
            "COMMITTED",
            payload,
            1,
            DigestUtils.sha256(payload),
            Instant.now()
        );

        snapshotRepository.insertSnapshot(oldRecord);
        snapshotRepository.insertSnapshot(newRecord);

        int pruned = snapshotRepository.pruneOldSnapshots(Duration.ofDays(7));
        assertThat(pruned).isEqualTo(1);

        List<PlayerSnapshotRecord> remaining = snapshotRepository.findRecentSnapshots(playerUuid, 10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).revision()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Pruning: deletes a backlog larger than one chunk")
    void testPruneOldSnapshotsInChunks() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        int backlog = 1001;
        for (int i = 0; i < backlog; i++) {
            snapshotRepository.insertSnapshot(new PlayerSnapshotRecord(UUID.randomUUID().toString(), playerUuid,
                i + 1L, 1L, UUID.randomUUID(), "server-a", "CHECKPOINT", "COMMITTED", payload, 1,
                DigestUtils.sha256(payload), Instant.now().minus(Duration.ofDays(10))));
        }

        assertThat(snapshotRepository.pruneOldSnapshots(Duration.ofDays(7))).isEqualTo(backlog);
        assertThat(snapshotRepository.findRecentSnapshots(playerUuid, 10)).isEmpty();
    }

    @Test
    @DisplayName("Valid Snapshot Selection: Corrupted snapshot is skipped and previous valid one is returned")
    void testFindLatestValidSnapshotWithCorruptRecordFallback() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        byte[] payload1 = "valid-inventory-rev-1".getBytes(StandardCharsets.UTF_8);
        byte[] payload2 = "corrupted-inventory-rev-2".getBytes(StandardCharsets.UTF_8);

        PlayerSnapshotRecord validRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            1L,
            1L,
            sessionId,
            "server-a",
            "CHECKPOINT",
            "COMMITTED",
            payload1,
            1,
            DigestUtils.sha256(payload1),
            Instant.now().minusSeconds(10)
        );

        PlayerSnapshotRecord corruptRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            2L,
            1L,
            sessionId,
            "server-a",
            "CHECKPOINT",
            "COMMITTED",
            payload2,
            1,
            "tampered-hash-does-not-match",
            Instant.now()
        );

        snapshotRepository.insertSnapshot(validRecord);
        snapshotRepository.insertSnapshot(corruptRecord);

        // findLatestSnapshot returns the raw newest row
        Optional<PlayerSnapshotRecord> latestOpt = snapshotRepository.findLatestSnapshot(playerUuid);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().revision()).isEqualTo(2L);

        // findLatestValidSnapshot validates the SHA-256 hash and skips the corrupted rev 2, returning valid rev 1!
        Optional<PlayerSnapshotRecord> validOpt = snapshotRepository.findLatestValidSnapshot(playerUuid);
        assertThat(validOpt).isPresent();
        assertThat(validOpt.get().revision()).isEqualTo(1L);
        assertThat(validOpt.get().payload()).isEqualTo(payload1);
    }

    @Test
    @DisplayName("Snapshot Status Priority: COMMITTED status is prioritized over SUPERSEDED for same revision")
    void testFindLatestSnapshotPrefersCommittedOverSupersededForSameRevision() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionIdA = UUID.randomUUID();
        UUID sessionIdB = UUID.randomUUID();

        byte[] payloadSuperseded = "superseded-gear".getBytes(StandardCharsets.UTF_8);
        byte[] payloadCommitted = "authoritative-gear".getBytes(StandardCharsets.UTF_8);

        PlayerSnapshotRecord supersededRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            5L,
            10L,
            sessionIdA,
            "server-lagging",
            "QUIT",
            "SUPERSEDED",
            payloadSuperseded,
            1,
            DigestUtils.sha256(payloadSuperseded),
            Instant.now().plusSeconds(2)
        );

        PlayerSnapshotRecord committedRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            5L,
            11L,
            sessionIdB,
            "server-authoritative",
            "QUIT",
            "COMMITTED",
            payloadCommitted,
            1,
            DigestUtils.sha256(payloadCommitted),
            Instant.now()
        );

        snapshotRepository.insertSnapshot(supersededRecord);
        snapshotRepository.insertSnapshot(committedRecord);

        Optional<PlayerSnapshotRecord> latest = snapshotRepository.findLatestSnapshot(playerUuid);
        assertThat(latest).isPresent();
        assertThat(latest.get().status()).isEqualTo("COMMITTED");
        assertThat(latest.get().payload()).isEqualTo(payloadCommitted);
    }

    @Test
    @DisplayName("SQL Desync Recovery: Journal superseded conflict stores snapshot to MySQL and auto-archives conflict")
    void testFinalSaveRecoveryPreservesSnapshotToMySQLOnEarlySupersededConflict(@TempDir Path tempDir) throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        var acquiredA = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "DesyncPlayer", "server-a", sessionA, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionA, acquiredA.session().fencingToken(), "ACTIVE");

        // Simulate player quitting server-a during SQL desync: entry stored on disk
        byte[] payloadA = "server-a-quit-inventory".getBytes(StandardCharsets.UTF_8);
        FinalSaveJournal.Entry entry = new FinalSaveJournal.Entry(
            acquiredA.session(), 0L, 1, payloadA, DigestUtils.sha256(payloadA));

        FinalSaveJournal journal = new FinalSaveJournal(tempDir, stateRepository);
        journal.store(entry);
        assertThat(journal.contains(playerUuid)).isTrue();

        // While server-a was desynced, server-b took ownership and committed rev 1
        stateRepository.releaseOwnership(playerUuid, sessionA, acquiredA.session().fencingToken(), "OFFLINE");
        UUID sessionB = UUID.randomUUID();
        var acquiredB = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "DesyncPlayer", "server-b", sessionB, Duration.ofSeconds(45));
        byte[] payloadB = "server-b-active-inventory".getBytes(StandardCharsets.UTF_8);
        stateRepository.commit(playerUuid, sessionB, acquiredB.session().fencingToken(), 0L,
            payloadB, 1, DigestUtils.sha256(payloadB), "QUIT", "server-b");

        // Now server-a runs recovery:
        CommitResult recoverResult = journal.recover(playerUuid);
        assertThat(recoverResult).isInstanceOf(CommitResult.Conflict.class);

        // 1. MySQL snapshot repository MUST contain the superseded snapshot from server-a with full items preserved!
        List<PlayerSnapshotRecord> snapshots = snapshotRepository.findRecentSnapshots(playerUuid, 10);
        assertThat(snapshots).anyMatch(s -> "SUPERSEDED".equals(s.status())
            && java.util.Arrays.equals(s.payload(), payloadA)
            && "server-a".equals(s.serverId()));

        // 2. Journal auto-archives the conflict to resolved directory
        assertThat(journal.hasConflict(playerUuid)).isTrue();
        assertThat(journal.archiveConflict(playerUuid)).isTrue();
        assertThat(journal.hasConflict(playerUuid)).isFalse();
    }

    @Test
    @DisplayName("Conflicting final save stays blocked and preserves captured bytes for review")
    void testPlayerStateServiceFlushPendingFinalSaveRetainsConflict(@TempDir Path tempDir) throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        var acquiredA = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "FlushPlayer", "server-a", sessionA, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionA, acquiredA.session().fencingToken(), "ACTIVE");

        byte[] payloadA = "unwritten-gear".getBytes(StandardCharsets.UTF_8);
        FinalSaveJournal.Entry entry = new FinalSaveJournal.Entry(
            acquiredA.session(), 0L, 1, payloadA, DigestUtils.sha256(payloadA));

        FinalSaveJournal journal = new FinalSaveJournal(tempDir, stateRepository);
        journal.store(entry);

        // Server B supersedes
        stateRepository.releaseOwnership(playerUuid, sessionA, acquiredA.session().fencingToken(), "OFFLINE");
        UUID sessionB = UUID.randomUUID();
        var acquiredB = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "FlushPlayer", "server-b", sessionB, Duration.ofSeconds(45));

        var sessions = new PlayerSessionService();
        var serializer = Mockito.mock(PlayerStateSerializer.class);
        var scheduler = Mockito.mock(PlatformScheduler.class);
        var modules = Mockito.mock(SyncModuleManager.class);

        try (var worker = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(stateRepository, serializer,
                new PlayerOperationQueue(worker), modules, sessions, scheduler, worker, null);
            service.enableFinalSaveJournal(tempDir);

            assertThat(service.hasPendingFinalSave(playerUuid)).isTrue();

            // flushPendingFinalSave runs during pre-login
            CommitResult flushResult = service.flushPendingFinalSave(playerUuid).get(5, TimeUnit.SECONDS);
            assertThat(flushResult).isInstanceOf(CommitResult.Conflict.class);

            // Archiving an attempted save is not proof that its data was committed.
            assertThat(service.hasPendingFinalSave(playerUuid)).isTrue();
            assertThat(service.hasFinalSaveConflict(playerUuid)).isTrue();
            assertThat(service.flushPendingFinalSave(playerUuid).get(5, TimeUnit.SECONDS))
                .isInstanceOf(CommitResult.Failed.class);
            assertThat(journal.readConflict(playerUuid).payload()).isEqualTo(payloadA);

            // And snapshot was recorded in MySQL!
            List<PlayerSnapshotRecord> snaps = snapshotRepository.findRecentSnapshots(playerUuid, 5);
            assertThat(snaps).anyMatch(s -> "SUPERSEDED".equals(s.status()) && java.util.Arrays.equals(s.payload(), payloadA));
        }
    }

    @Test
    @DisplayName("Snapshot Status Priority: COMMITTED status is prioritized over higher SUPERSEDED revision")
    void testFindLatestValidSnapshotPrefersCommittedOverHigherSupersededRevision() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        byte[] payloadSuperseded = "superseded-rev-5".getBytes(StandardCharsets.UTF_8);
        byte[] payloadCommitted = "committed-rev-4".getBytes(StandardCharsets.UTF_8);

        PlayerSnapshotRecord supersededRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            5L, // Higher revision
            10L,
            sessionId,
            "server-lagging",
            "QUIT",
            "SUPERSEDED",
            payloadSuperseded,
            1,
            DigestUtils.sha256(payloadSuperseded),
            Instant.now()
        );

        PlayerSnapshotRecord committedRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            4L, // Lower revision, but COMMITTED!
            9L,
            sessionId,
            "server-authoritative",
            "QUIT",
            "COMMITTED",
            payloadCommitted,
            1,
            DigestUtils.sha256(payloadCommitted),
            Instant.now().minusSeconds(10)
        );

        snapshotRepository.insertSnapshot(supersededRecord);
        snapshotRepository.insertSnapshot(committedRecord);

        // findLatestValidSnapshot must pick COMMITTED (rev 4) over SUPERSEDED (rev 5)!
        Optional<PlayerSnapshotRecord> latestValid = snapshotRepository.findLatestValidSnapshot(playerUuid);
        assertThat(latestValid).isPresent();
        assertThat(latestValid.get().status()).isEqualTo("COMMITTED");
        assertThat(latestValid.get().revision()).isEqualTo(4L);
        assertThat(latestValid.get().payload()).isEqualTo(payloadCommitted);
    }

    @Test
    @DisplayName("Highest Revision Recovery: findHighestRevisionSnapshot retrieves higher revision SUPERSEDED snapshot to recover newer items")
    void testFindHighestRevisionSnapshotRecoversSupersededItems() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        byte[] payloadSupersededWithMace = "superseded-rev-5-with-mace-and-shulkers".getBytes(StandardCharsets.UTF_8);
        byte[] payloadCommittedOld = "committed-rev-4-older-inventory".getBytes(StandardCharsets.UTF_8);

        String snapshotIdSuperseded = UUID.randomUUID().toString();
        PlayerSnapshotRecord supersededRecord = new PlayerSnapshotRecord(
            snapshotIdSuperseded,
            playerUuid,
            5L, // Higher revision with newer items!
            10L,
            sessionId,
            "server-a",
            "QUIT",
            "SUPERSEDED",
            payloadSupersededWithMace,
            1,
            DigestUtils.sha256(payloadSupersededWithMace),
            Instant.now()
        );

        PlayerSnapshotRecord committedRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            4L, // Lower revision
            9L,
            sessionId,
            "server-a",
            "QUIT",
            "COMMITTED",
            payloadCommittedOld,
            1,
            DigestUtils.sha256(payloadCommittedOld),
            Instant.now().minusSeconds(10)
        );

        snapshotRepository.insertSnapshot(supersededRecord);
        snapshotRepository.insertSnapshot(committedRecord);

        // findHighestRevisionSnapshot must return the SUPERSEDED rev 5 snapshot!
        Optional<PlayerSnapshotRecord> highest = snapshotRepository.findHighestRevisionSnapshot(playerUuid);
        assertThat(highest).isPresent();
        assertThat(highest.get().status()).isEqualTo("SUPERSEDED");
        assertThat(highest.get().revision()).isEqualTo(5L);
        assertThat(highest.get().payload()).isEqualTo(payloadSupersededWithMace);

        // findSnapshotById must retrieve the exact record
        Optional<PlayerSnapshotRecord> byId = snapshotRepository.findSnapshotById(snapshotIdSuperseded);
        assertThat(byId).isPresent();
        assertThat(byId.get().snapshotId()).isEqualTo(snapshotIdSuperseded);
        assertThat(byId.get().payload()).isEqualTo(payloadSupersededWithMace);
    }

    @Test
    @DisplayName("Corruption recovery cannot relabel an older inventory as the current revision")
    void testCorruptionRecoveryRejectsOlderInventory() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) stateRepository.acquireOwnership(
            playerUuid, "SelfHealPlayer", "server-a", sessionA, Duration.ofSeconds(45));
        stateRepository.updateSessionState(playerUuid, sessionA, acquired.session().fencingToken(), "ACTIVE");

        // Insert valid snapshot at rev 8 into MySQL snapshots table
        byte[] validPayload = "valid-snapshot-rev-8".getBytes(StandardCharsets.UTF_8);
        String validHash = DigestUtils.sha256(validPayload);
        PlayerSnapshotRecord snapRecord = new PlayerSnapshotRecord(
            UUID.randomUUID().toString(),
            playerUuid,
            8L,
            acquired.session().fencingToken(),
            sessionA,
            "server-a",
            "CHECKPOINT",
            "COMMITTED",
            validPayload,
            1,
            validHash,
            Instant.now().minusSeconds(30)
        );
        snapshotRepository.insertSnapshot(snapRecord);

        // Update friendlysync_players to revision 10 with corrupted payload/hash
        byte[] corruptedPayload = "corrupted-payload-data".getBytes(StandardCharsets.UTF_8);
        try (PreparedStatement ps = rootConnection.prepareStatement("""
            UPDATE friendlysync_players
            SET revision = 10, payload = ?, payload_hash = 'tampered-hash-that-does-not-match'
            WHERE uuid = ?
            """)) {
            ps.setBytes(1, corruptedPayload);
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }

        var sessions = new PlayerSessionService();
        var serializer = Mockito.mock(PlayerStateSerializer.class);
        var scheduler = Mockito.mock(PlatformScheduler.class);
        var modules = Mockito.mock(SyncModuleManager.class);

        PlayerStateSnapshot deserializedSnapshot = new PlayerStateSnapshot(
            playerUuid, 8L, acquired.session().fencingToken(), null, null, null, null, null, null, null, null, null, null, null, null, Instant.now(), 1
        );
        Mockito.when(serializer.deserialize(validPayload)).thenReturn(deserializedSnapshot);

        try (var worker = Executors.newFixedThreadPool(2)) {
            var service = new PlayerStateService(stateRepository, serializer,
                new PlayerOperationQueue(worker), modules, sessions, scheduler, worker, null);

            // A valid older backup does not prove it contains the current committed inventory.
            LoadResult loadResult = service.loadState(playerUuid).get(5, TimeUnit.SECONDS);
            assertThat(loadResult).isInstanceOf(LoadResult.Corrupted.class);
            var unchanged = stateRepository.find(playerUuid).orElseThrow();
            assertThat(unchanged.revision()).isEqualTo(10L);
            assertThat(unchanged.payload()).isEqualTo(corruptedPayload);
            Mockito.verify(serializer, Mockito.never()).serialize(Mockito.any());
        }
    }
}
