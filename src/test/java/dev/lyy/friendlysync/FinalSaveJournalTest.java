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
import java.nio.file.*;
import dev.lyy.friendlysync.session.FinalSaveJournal;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class FinalSaveJournalTest {

    private Connection rootConnection;
    private DatabaseManager mockDatabaseManager;
    private PlayerStateRepository repository;

    private static final String JDBC_URL = "jdbc:h2:mem:journal_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

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

    @TempDir Path directory;

    private FinalSaveJournal.Entry entry() throws Exception {
        UUID uuid = UUID.randomUUID();
        var acquired = (OwnershipResult.Acquired) repository.acquireOwnership(uuid, "Player", "source",
            UUID.randomUUID(), Duration.ofSeconds(45));
        repository.updateSessionState(uuid, acquired.session().sessionId(), acquired.session().fencingToken(), "ACTIVE");
        byte[] payload = "latest-inventory-and-xp".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new FinalSaveJournal.Entry(acquired.session(), 0, 3, payload, DigestUtils.sha256(payload));
    }

    @Test
    void explicitRecoveryReleasesMatchingExpiredOwnerAndFencesLateSaves() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository, "source");
        journal.store(entry);
        byte[] selected = new byte[]{7};
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 0, selected, 3, DigestUtils.sha256(selected));
        Files.move(directory.resolve(id.playerUuid() + ".pending"), directory.resolve(id.playerUuid() + ".conflict"));
        try (var stmt = rootConnection.createStatement()) {
            stmt.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00'");
        }
        assertThat(journal.resolveConflict(id.playerUuid(), false)).isFalse();
        assertThat(repository.find(id.playerUuid()).orElseThrow().ownerServer()).isEqualTo("source");
        assertThat(journal.resolveConflict(id.playerUuid(), true, DigestUtils.sha256(selected))).isTrue();
        var row = repository.find(id.playerUuid()).orElseThrow();
        assertThat(row.ownerServer()).isNull();
        assertThat(row.activeSessionId()).isNull();
        assertThat(row.fencingToken()).isEqualTo(id.fencingToken() + 1);
        assertThat(row.revision()).isEqualTo(1);
        assertThat(row.payload()).isEqualTo(selected);
        assertThat(repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 1,
            entry.payload(), 3, entry.hash())).isInstanceOf(CommitResult.Conflict.class);
        assertThat(journal.hasConflict(id.playerUuid())).isFalse();
        try (var files = Files.walk(directory.resolve("resolved"))) {
            var copy = files.filter(p -> p.getFileName().toString().equals("database.payload")).findFirst().orElseThrow();
            assertThat(Files.readAllBytes(copy)).isEqualTo(selected);
            assertThat(Files.exists(copy.resolveSibling("original.conflict"))).isTrue();
        }
    }

    @Test
    void explicitRecoveryRejectsLiveLeaseAndDifferentBackend() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository, "source");
        journal.store(entry);
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 0, entry.payload(), 3, entry.hash());
        Files.move(directory.resolve(id.playerUuid() + ".pending"), directory.resolve(id.playerUuid() + ".conflict"));
        assertThatThrownBy(() -> journal.resolveConflict(id.playerUuid(), true)).isInstanceOf(java.io.IOException.class);
        try (var stmt = rootConnection.createStatement()) {
            stmt.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00'");
        }
        var otherBackend = new FinalSaveJournal(directory, repository, "destination");
        assertThatThrownBy(() -> otherBackend.resolveConflict(id.playerUuid(), true)).isInstanceOf(java.io.IOException.class);
        assertThat(journal.hasConflict(id.playerUuid())).isTrue();
        assertThat(repository.find(id.playerUuid()).orElseThrow().ownerServer()).isEqualTo("source");
    }

    @Test
    void reviewedReleaseRejectsConcurrentCommitAndLeaseRenewal() throws Exception {
        var entry = entry();
        var id = entry.identity();
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 0, entry.payload(), 3, entry.hash());
        try (var stmt = rootConnection.createStatement()) {
            stmt.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMP '2000-01-01 00:00:00'");
        }
        var beforeCommit = repository.find(id.playerUuid()).orElseThrow();
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 1, new byte[]{8}, 3, DigestUtils.sha256(new byte[]{8}));
        assertThat(repository.releaseReviewedConflict(beforeCommit)).isFalse();
        var beforeRenewal = repository.find(id.playerUuid()).orElseThrow();
        try (var stmt = rootConnection.createStatement()) {
            stmt.executeUpdate("UPDATE friendlysync_players SET lease_expires_at = TIMESTAMP '2099-01-01 00:00:00'");
        }
        assertThat(repository.releaseReviewedConflict(beforeRenewal)).isFalse();
        assertThat(repository.find(id.playerUuid()).orElseThrow().ownerServer()).isEqualTo("source");
    }

    @Test
    void changedSqlPayloadCannotBeAcceptedUsingStaleValidation() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 0, entry.payload(), 3, entry.hash());
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        Files.move(directory.resolve(id.playerUuid() + ".pending"), directory.resolve(id.playerUuid() + ".conflict"));
        assertThatThrownBy(() -> journal.resolveConflict(id.playerUuid(), true, "stale-hash")).isInstanceOf(java.io.IOException.class);
        assertThat(journal.hasConflict(id.playerUuid())).isTrue();
    }

    @Test
    void identicalConflictCanBeArchivedWithoutReleasingNewOwner() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        assertThat(journal.commit(entry)).isInstanceOf(CommitResult.Success.class);
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        repository.acquireOwnership(id.playerUuid(), "Player", "next", UUID.randomUUID(), Duration.ofSeconds(45));
        Files.move(directory.resolve(id.playerUuid() + ".pending"), directory.resolve(id.playerUuid() + ".conflict"));
        var before = repository.find(id.playerUuid()).orElseThrow();
        assertThat(journal.resolveConflict(id.playerUuid(), false)).isTrue();
        assertThat(journal.hasConflict(id.playerUuid())).isFalse();
        assertThat(repository.find(id.playerUuid()).orElseThrow()).isEqualTo(before);
        try (var files = Files.walk(directory.resolve("resolved"))) {
            var copy = files.filter(p -> p.getFileName().toString().equals("database.payload")).findFirst().orElseThrow();
            assertThat(Files.readAllBytes(copy)).isEqualTo(entry.payload());
        }
    }

    @Test
    void differentConflictRequiresOfflineExplicitSelectionAndKeepsSql() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), 0, new byte[]{7}, 3, DigestUtils.sha256(new byte[]{7}));
        Files.move(directory.resolve(id.playerUuid() + ".pending"), directory.resolve(id.playerUuid() + ".conflict"));
        assertThat(journal.resolveConflict(id.playerUuid(), false)).isFalse();
        assertThatThrownBy(() -> journal.resolveConflict(id.playerUuid(), true)).isInstanceOf(java.io.IOException.class);
        assertThat(journal.hasConflict(id.playerUuid())).isTrue();
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        var before = repository.find(id.playerUuid()).orElseThrow();
        assertThat(journal.resolveConflict(id.playerUuid(), true)).isTrue();
        assertThat(repository.find(id.playerUuid()).orElseThrow()).isEqualTo(before);
        try (var files = Files.walk(directory.resolve("resolved"))) {
            assertThat(files.filter(p -> p.getFileName().toString().equals("original.conflict")).count()).isEqualTo(1);
        }
    }

    @Test
    void pendingFinalSaveCannotBeBypassedByResolution() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        assertThatThrownBy(() -> journal.resolveConflict(entry.identity().playerUuid(), true)).isInstanceOf(java.io.IOException.class);
        assertThat(journal.contains(entry.identity().playerUuid())).isTrue();
    }

    @Test
    void releasedOwnershipRejectsLateWriterEvenBeforeNextLogin() throws Exception {
        var entry = entry();
        var id = entry.identity();
        assertThat(repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE")).isTrue();
        assertThat(repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), entry.revision(),
            entry.payload(), entry.version(), entry.hash())).isInstanceOf(CommitResult.Conflict.class);
        assertThat(repository.find(id.playerUuid()).orElseThrow().revision()).isZero();
    }

    @Test
    void existingConflictIsNeverOverwrittenByAnotherRecovery() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        byte[] preserved = {9, 8, 7};
        var conflict = directory.resolve(entry.identity().playerUuid() + ".conflict");
        Files.write(conflict, preserved);
        var id = entry.identity();
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        assertThatThrownBy(() -> journal.recover(id.playerUuid())).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(conflict)).isEqualTo(preserved);
        assertThat(journal.contains(id.playerUuid())).isTrue();
    }

    @Test
    void diskFailureDoesNotWriteSqlAndRetainedMemoryCanBeRetried() throws Exception {
        var entry = entry();
        var sessions = new dev.lyy.friendlysync.session.PlayerSessionService();
        sessions.registerSession(entry.identity(), 0, "Player", dev.lyy.friendlysync.model.SessionState.SAVING);
        var serializer = Mockito.mock(dev.lyy.friendlysync.serialization.PlayerStateSerializer.class);
        Mockito.when(serializer.serialize(Mockito.any())).thenReturn(entry.payload());
        var snapshot = new dev.lyy.friendlysync.model.PlayerStateSnapshot(
            entry.identity().playerUuid(), 0, entry.identity().fencingToken(), null, null, null, null, null,
            java.util.List.of(), null, null, null, null, null, java.util.Map.of(), java.time.Instant.now(), 3);
        Path journalDirectory = directory.resolve("journal");
        try (var worker = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var service = new dev.lyy.friendlysync.session.PlayerStateService(repository, serializer,
                new dev.lyy.friendlysync.scheduler.PlayerOperationQueue(worker),
                Mockito.mock(dev.lyy.friendlysync.sync.SyncModuleManager.class), sessions,
                Mockito.mock(dev.lyy.friendlysync.scheduler.PlatformScheduler.class), worker, null);
            service.enableFinalSaveJournal(journalDirectory);
            Files.delete(journalDirectory); // Empty test directory only.
            Files.write(journalDirectory, new byte[] {1}); // Simulate an unwritable journal location.
            assertThat(service.commitDirect(snapshot, entry.identity()).get(3, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(CommitResult.Failed.class);
            assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().revision()).isZero();
            assertThat(service.hasPendingFinalSave(entry.identity().playerUuid())).isTrue();
            sessions.removeSession(entry.identity());
            Files.delete(journalDirectory);
            Files.createDirectory(journalDirectory);
            service.recoverPendingFinalSaves().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(service.hasUnrecoveredFinalSaves()).isFalse();
            assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().payload()).isEqualTo(entry.payload());
        }
    }

    @Test
    void stateServiceJournalsFailureAndRetryWorkerRecoversWithoutLivePlayer() throws Exception {
        var entry = entry();
        var sessions = new dev.lyy.friendlysync.session.PlayerSessionService();
        sessions.registerSession(entry.identity(), 0, "Player", dev.lyy.friendlysync.model.SessionState.SAVING);
        var serializer = Mockito.mock(dev.lyy.friendlysync.serialization.PlayerStateSerializer.class);
        Mockito.when(serializer.serialize(Mockito.any())).thenReturn(entry.payload());
        var snapshot = new dev.lyy.friendlysync.model.PlayerStateSnapshot(
            entry.identity().playerUuid(), 0, entry.identity().fencingToken(), null, null, null, null, null,
            java.util.List.of(), null, null, null, null, null, java.util.Map.of(), java.time.Instant.now(), 3);
        try (var worker = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var service = new dev.lyy.friendlysync.session.PlayerStateService(repository, serializer,
                new dev.lyy.friendlysync.scheduler.PlayerOperationQueue(worker),
                Mockito.mock(dev.lyy.friendlysync.sync.SyncModuleManager.class), sessions,
                Mockito.mock(dev.lyy.friendlysync.scheduler.PlatformScheduler.class), worker, null);
            service.enableFinalSaveJournal(directory);
            Mockito.when(mockDatabaseManager.getConnection()).thenThrow(new java.sql.SQLException("offline"));
            assertThat(service.commitDirect(snapshot, entry.identity()).get(3, java.util.concurrent.TimeUnit.SECONDS))
                .isInstanceOf(CommitResult.Failed.class);
            assertThat(service.hasPendingFinalSave(entry.identity().playerUuid())).isTrue();
            assertThat(new FinalSaveJournal(directory, repository).read(entry.identity().playerUuid()).payload()).isEqualTo(entry.payload());
            sessions.removeSession(entry.identity());
            Mockito.doAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", "")).when(mockDatabaseManager).getConnection();
            service.recoverPendingFinalSaves().get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(service.hasUnrecoveredFinalSaves()).isFalse();
            assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().payload()).isEqualTo(entry.payload());
            assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().ownerServer()).isNull();
        }
    }

    @Test
    void outagePreservesSnapshotAndRestartReplaysIt() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        Mockito.when(mockDatabaseManager.getConnection()).thenThrow(new java.sql.SQLException("offline"));
        assertThat(journal.recover(entry.identity().playerUuid())).isInstanceOf(CommitResult.Failed.class);
        assertThat(journal.read(entry.identity().playerUuid()).payload()).isEqualTo(entry.payload());
        Mockito.doAnswer(inv -> DriverManager.getConnection(JDBC_URL, "sa", "")).when(mockDatabaseManager).getConnection();
        var restarted = new FinalSaveJournal(directory, repository);
        assertThat(restarted.recover(entry.identity().playerUuid())).isInstanceOf(CommitResult.Success.class);
        var row = repository.find(entry.identity().playerUuid()).orElseThrow();
        assertThat(row.payload()).isEqualTo(entry.payload());
        assertThat(row.ownerServer()).isNull();
        assertThat(restarted.pendingPlayers()).isEmpty();
    }

    @Test
    void lostSqlResponseDoesNotIncrementRevisionTwice() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        assertThat(journal.commit(entry)).isInstanceOf(CommitResult.Success.class);
        assertThat(journal.recover(entry.identity().playerUuid())).isInstanceOf(CommitResult.Success.class);
        assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().revision()).isEqualTo(1);
    }

    @Test
    void newerSessionIsNeverOverwrittenOrReleased() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        var id = entry.identity();
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        var newer = (OwnershipResult.Acquired) repository.acquireOwnership(id.playerUuid(), "Player", "destination",
            UUID.randomUUID(), Duration.ofSeconds(45));
        byte[] payload = "newer-gameplay".getBytes();
        repository.commit(id.playerUuid(), newer.session().sessionId(), newer.session().fencingToken(),
            0, payload, 3, DigestUtils.sha256(payload));
        assertThat(journal.recover(id.playerUuid())).isInstanceOf(CommitResult.Conflict.class);
        var row = repository.find(id.playerUuid()).orElseThrow();
        assertThat(row.payload()).isEqualTo(payload);
        assertThat(row.ownerServer()).isEqualTo("destination");
        assertThat(journal.contains(id.playerUuid())).isFalse();
        assertThat(journal.hasConflict(id.playerUuid())).isTrue();
        assertThat(journal.pendingPlayers()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve(id.playerUuid() + ".conflict"))).isNotEmpty();
    }

    @Test
    void confirmedSaveCanBeAcknowledgedAfterDestinationHasAlreadySaved() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        assertThat(journal.commit(entry)).isInstanceOf(CommitResult.Success.class);
        var id = entry.identity();
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        var next = (OwnershipResult.Acquired) repository.acquireOwnership(id.playerUuid(), "Player", "next",
            UUID.randomUUID(), Duration.ofSeconds(45));
        repository.commit(id.playerUuid(), next.session().sessionId(), next.session().fencingToken(),
            1, new byte[] {4}, 3, DigestUtils.sha256(new byte[] {4}));
        journal.acknowledgeCommitted(entry);
        assertThat(journal.pendingPlayers()).isEmpty();
        assertThat(repository.find(id.playerUuid()).orElseThrow().ownerServer()).isEqualTo("next");
    }

    @Test
    void acknowledgmentCannotDeleteDifferentPendingSnapshot() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        var different = new FinalSaveJournal.Entry(entry.identity(), 1, 3, new byte[] {7}, DigestUtils.sha256(new byte[] {7}));
        journal.store(different);
        journal.acknowledgeCommitted(entry);
        assertThat(journal.read(entry.identity().playerUuid()).hash()).isEqualTo(different.hash());
    }

    @Test
    void corruptJournalIsRejectedWithoutWritingSql() throws Exception {
        var entry = entry();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        Path file = directory.resolve(entry.identity().playerUuid() + ".pending");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        assertThatThrownBy(() -> journal.recover(entry.identity().playerUuid())).isInstanceOf(java.io.IOException.class);
        assertThat(repository.find(entry.identity().playerUuid()).orElseThrow().revision()).isZero();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void expiredGameplayLeaseBlocksLoginUntilJournalRecovery() throws Exception {
        var entry = entry();
        UUID uuid = entry.identity().playerUuid();
        try (PreparedStatement ps = rootConnection.prepareStatement(
            "UPDATE friendlysync_players SET lease_expires_at = TIMESTAMPADD(SECOND, -60, CURRENT_TIMESTAMP) WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
        assertThat(repository.acquireOwnership(uuid, "Player", "destination", UUID.randomUUID(), Duration.ofSeconds(45)))
            .isInstanceOf(OwnershipResult.AlreadyOwned.class);
        assertThat(repository.releaseOwnershipIfOwner(uuid, "source", "OFFLINE")).isFalse();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        assertThat(journal.recover(uuid)).isInstanceOf(CommitResult.Success.class);
        assertThat(repository.acquireOwnership(uuid, "Player", "destination", UUID.randomUUID(), Duration.ofSeconds(45)))
            .isInstanceOf(OwnershipResult.Acquired.class);
    }

    @Test
    void releasedSessionWithNoConfirmedCommitIsNotReplayed() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        assertThat(journal.recover(id.playerUuid())).isInstanceOf(CommitResult.Conflict.class);
        assertThat(repository.find(id.playerUuid()).orElseThrow().revision()).isZero();
    }

    @Test
    void recoverReturnsSuccessWhenFileMissingDueToConcurrentDeletion() throws Exception {
        var journal = new FinalSaveJournal(directory, repository);
        UUID uuid = UUID.randomUUID();
        // Pending file does not exist or was concurrently deleted
        CommitResult result = journal.recover(uuid);
        assertThat(result).isInstanceOf(CommitResult.Success.class);
    }

    @Test
    void acknowledgeHandlesMissingFileGracefully() throws Exception {
        var journal = new FinalSaveJournal(directory, repository);
        UUID uuid = UUID.randomUUID();
        // File does not exist; should return cleanly without throwing
        journal.acknowledge(uuid);
    }

    @Test
    void alreadyCommittedSaveWithReleasedOwnerRecoversAsSuccessWithoutConflict() throws Exception {
        var entry = entry();
        var id = entry.identity();
        var journal = new FinalSaveJournal(directory, repository);
        journal.store(entry);
        repository.commit(id.playerUuid(), id.sessionId(), id.fencingToken(), entry.revision(), entry.payload(), 3, entry.hash());
        repository.releaseOwnership(id.playerUuid(), id.sessionId(), id.fencingToken(), "OFFLINE");
        var result = journal.recover(id.playerUuid());
        assertThat(result).isInstanceOf(CommitResult.Success.class);
        assertThat(journal.hasConflict(id.playerUuid())).isFalse();
        assertThat(journal.contains(id.playerUuid())).isFalse();
    }
}
