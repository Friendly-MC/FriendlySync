package dev.lyy.friendlysync.session;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.api.event.FriendlySyncPostSaveEvent;
import dev.lyy.friendlysync.api.event.FriendlySyncPreSaveEvent;
import dev.lyy.friendlysync.database.repository.PersistedPlayerState;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.metrics.MetricsService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.DigestUtils;
import dev.lyy.friendlysync.util.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Service managing loading, capturing, hashing, and optimistic revision committing of player snapshots.
 */
public final class PlayerStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStateService.class);

    private FinalSaveJournal finalSaveJournal;
    private final java.util.Set<UUID> reviewedConflicts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, FinalSaveJournal.Entry> confirmedFinalSaves = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> recoveryWarnings = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean reportRecoveryIssue(UUID uuid) {
        long now = System.nanoTime();
        Long previous = recoveryWarnings.get(uuid);
        if (previous != null && now - previous < java.util.concurrent.TimeUnit.MINUTES.toNanos(5)) return false;
        recoveryWarnings.put(uuid, now);
        return true;
    }
    private record RejectedFinalSave(PlayerStateSnapshot snapshot, SessionIdentity identity) {}
    // Admission failures occur before serialization/journaling. Retain that immutable
    // capture for the retry worker instead of losing the final inventory under overload.
    private final java.util.Map<UUID, RejectedFinalSave> rejectedFinalSaves = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, FinalSaveJournal.Entry> unwrittenFinalSaves = new java.util.concurrent.ConcurrentHashMap<>();

    public void enableFinalSaveJournal(java.nio.file.Path directory) throws java.io.IOException {
        enableFinalSaveJournal(directory, null);
    }

    public void enableFinalSaveJournal(java.nio.file.Path directory, String localServerId) throws java.io.IOException {
        finalSaveJournal = new FinalSaveJournal(directory, repository, localServerId);
    }

    public boolean hasPendingFinalSave(UUID uuid) {
        return rejectedFinalSaves.containsKey(uuid) || unwrittenFinalSaves.containsKey(uuid) || (finalSaveJournal != null && (finalSaveJournal.contains(uuid) || finalSaveJournal.hasConflict(uuid)));
    }

    /** Players whose final save is still journaled or in conflict on this backend. */
    public java.util.Set<UUID> unresolvedFinalSavePlayers() throws java.io.IOException {
        java.util.Set<UUID> players = new java.util.HashSet<>(rejectedFinalSaves.keySet());
        players.addAll(unwrittenFinalSaves.keySet());
        if (finalSaveJournal != null) {
            players.addAll(finalSaveJournal.pendingPlayers());
            players.addAll(finalSaveJournal.conflictPlayers());
        }
        return players;
    }

    public boolean hasFinalSaveConflict(UUID uuid) {
        return finalSaveJournal != null && finalSaveJournal.hasConflict(uuid);
    }

    public CompletableFuture<CommitResult> flushPendingFinalSave(UUID uuid) {
        return operationQueue.submit(uuid, () -> CompletableFuture.supplyAsync(() -> {
            try {
                journalRejectedFinalSave(uuid);
                FinalSaveJournal.Entry unwritten = unwrittenFinalSaves.get(uuid);
                if (unwritten != null) {
                    if (finalSaveJournal == null) throw new IllegalStateException("Recovery journal is unavailable");
                    finalSaveJournal.store(unwritten);
                    unwrittenFinalSaves.remove(uuid, unwritten);
                }
                if (finalSaveJournal != null && finalSaveJournal.contains(uuid)) {
                    CommitResult result = finalSaveJournal.recover(uuid);
                    if (result instanceof CommitResult.Success) {
                        confirmedFinalSaves.remove(uuid);
                        recoveryWarnings.remove(uuid);
                    }
                    return result;
                }
                if (finalSaveJournal != null && finalSaveJournal.hasConflict(uuid)) {
                    return new CommitResult.Failed(new IllegalStateException("Final-save conflict requires administrator review"));
                }
                return new CommitResult.Success(0, 0);
            } catch (Exception e) {
                LOGGER.warn("Failed to flush pending final save for {}: {}", uuid, e.getMessage());
                return new CommitResult.Failed(e);
            }
        }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool()));
    }
    public CompletableFuture<String> reviewRecovery(UUID uuid, boolean acceptCurrent) {
        return operationQueue.submit(uuid, () -> CompletableFuture.supplyAsync(() -> {
            if (finalSaveJournal == null) return "Recovery journal is not enabled.";
            if (sessionService.getLocalSession(uuid).isPresent() || sessionService.isPlayerOnline(uuid))
                return "Player has a local session; wait for their final save before resolving recovery.";
            try {
                if (rejectedFinalSaves.containsKey(uuid) || unwrittenFinalSaves.containsKey(uuid) || finalSaveJournal.contains(uuid))
                    return "Final save is still pending. Resolve storage errors first; do not force-unlock.";
                if (!finalSaveJournal.hasConflict(uuid)) return "No recovery file blocks this player on this backend.";
                String validatedHash = null;
                if (acceptCurrent) {
                    var row = repository.find(uuid).orElse(null);
                    if (row == null || row.payload() == null || !serializer.deserialize(row.payload()).playerUuid().equals(uuid))
                        return "Current database snapshot is missing or invalid; recovery remains blocked.";
                    validatedHash = DigestUtils.sha256(row.payload());
                }
                if (finalSaveJournal.resolveConflict(uuid, acceptCurrent, validatedHash)) {
                    reviewedConflicts.remove(uuid);
                    recoveryWarnings.remove(uuid);
                    LOGGER.warn("Recovery resolved for {} using {}; backups preserved in pending-saves/resolved. Inventory unchanged; matching expired ownership may have been released.", uuid, acceptCurrent ? "administrator-selected current SQL data" : "identical committed SQL data");
                    return "Recovery block cleared on this backend. Database inventory unchanged; backups in pending-saves/resolved.";
                }
                return "Conflicting snapshot needs review. After reviewing current SQL data, use /friendlysync recovery " + uuid + " accept-current to keep SQL data and archive the old snapshot. This does not restore missing items.";
            } catch (Exception error) {
                LOGGER.error("Cannot resolve recovery for {}", uuid, error);
                return "Recovery remains blocked: " + error.getMessage();
            }
        }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool()));
    }

    public boolean hasUnrecoveredFinalSaves() {
        if (!rejectedFinalSaves.isEmpty() || !unwrittenFinalSaves.isEmpty()) return true;
        if (finalSaveJournal == null) return false;
        try {
            return !finalSaveJournal.pendingPlayers().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    public void acknowledgeFinalSave(UUID uuid) {
        if (finalSaveJournal == null) return;
        try {
            FinalSaveJournal.Entry confirmed = confirmedFinalSaves.get(uuid);
            if (confirmed != null) {
                finalSaveJournal.acknowledgeCommitted(confirmed);
                confirmedFinalSaves.remove(uuid, confirmed);
            } else {
                finalSaveJournal.acknowledge(uuid);
            }
        } catch (Exception e) {
            LOGGER.error("Could not acknowledge final-save journal for {}", uuid, e);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean recoveringFinalSaves = new java.util.concurrent.atomic.AtomicBoolean();

    public CompletableFuture<Void> recoverPendingFinalSaves() {
        if (finalSaveJournal == null || !recoveringFinalSaves.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        java.util.List<CompletableFuture<?>> recoveries = new java.util.ArrayList<>();
        try {
            java.util.Set<UUID> pending = new java.util.HashSet<>(finalSaveJournal.pendingPlayers());
            pending.addAll(unwrittenFinalSaves.keySet());
            pending.addAll(rejectedFinalSaves.keySet());
            for (UUID conflict : finalSaveJournal.conflictPlayers()) {
                if (reviewedConflicts.add(conflict)) {
                    LOGGER.error("Final-save conflict for {} requires administrator review; retained on disk without automatic replay", conflict);
                }
            }
            for (UUID uuid : pending) {
                recoveries.add(operationQueue.submit(uuid, () -> CompletableFuture.runAsync(() -> {
                    if (sessionService.getLocalSession(uuid).isPresent()) return;
                    try {
                        journalRejectedFinalSave(uuid);
                        FinalSaveJournal.Entry unwritten = unwrittenFinalSaves.get(uuid);
                        if (unwritten != null) {
                            finalSaveJournal.store(unwritten);
                            unwrittenFinalSaves.remove(uuid, unwritten);
                        }
                        CommitResult result = finalSaveJournal.recover(uuid);
                        if (result instanceof CommitResult.Success) {
                            recoveryWarnings.remove(uuid);
                            confirmedFinalSaves.remove(uuid);
                        } else if (reportRecoveryIssue(uuid)) {
                            LOGGER.error("Final-save recovery for {} remains unresolved: {} (repeated reports limited to every 5 minutes)", uuid, result);
                        }
                    } catch (Exception e) {
                        if (reportRecoveryIssue(uuid)) LOGGER.error("Cannot recover final-save journal for {}", uuid, e);
                    }
                }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool())));
            }
        } catch (Exception e) {
            recoveries.add(CompletableFuture.failedFuture(e));
        }
        // Include already submitted work even when scanning/submission fails partway.
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, error) -> recoveringFinalSaves.set(false));
    }
    private final Object captureLock = new Object();
    private volatile boolean shuttingDown;
    private final java.util.Set<CompletableFuture<CommitResult>> pendingCaptures = new java.util.HashSet<>();
    private final java.util.Set<CompletableFuture<String>> pendingRestores = new java.util.HashSet<>();

    /** Unblocks queues waiting for server ticks, without cancelling database writes. */
    public void beginShutdown() {
        synchronized (captureLock) {
            shuttingDown = true;
            for (CompletableFuture<CommitResult> capture : java.util.List.copyOf(pendingCaptures)) {
                capture.complete(new CommitResult.Failed(new IllegalStateException("Server is shutting down")));
            }
            pendingCaptures.clear();
            for (CompletableFuture<String> restore : java.util.List.copyOf(pendingRestores)) {
                restore.complete("Server is shutting down; restore refused before capture.");
            }
            pendingRestores.clear();
        }
    }

    private final PlayerStateRepository repository;
    private final PlayerStateSerializer serializer;
    private final PlayerOperationQueue operationQueue;
    private final SyncModuleManager syncModuleManager;
    private final PlayerSessionService sessionService;
    private final PlatformScheduler scheduler;
    private final ExecutorService workerExecutor;
    private final MetricsService metricsService;

    public PlayerStateService(
        PlayerStateRepository repository,
        PlayerStateSerializer serializer,
        PlayerOperationQueue operationQueue,
        SyncModuleManager syncModuleManager,
        PlayerSessionService sessionService,
        PlatformScheduler scheduler
    ) {
        this(repository, serializer, operationQueue, syncModuleManager, sessionService, scheduler, null, null);
    }

    public PlayerStateService(
        PlayerStateRepository repository,
        PlayerStateSerializer serializer,
        PlayerOperationQueue operationQueue,
        SyncModuleManager syncModuleManager,
        PlayerSessionService sessionService,
        PlatformScheduler scheduler,
        ExecutorService workerExecutor,
        MetricsService metricsService
    ) {
        this.repository = Preconditions.checkNotNull(repository, "repository cannot be null");
        this.serializer = Preconditions.checkNotNull(serializer, "serializer cannot be null");
        this.operationQueue = Preconditions.checkNotNull(operationQueue, "operationQueue cannot be null");
        this.syncModuleManager = Preconditions.checkNotNull(syncModuleManager, "syncModuleManager cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler cannot be null");
        this.workerExecutor = workerExecutor;
        this.metricsService = metricsService;
    }

    /**
     * Loads persisted state for a player, verifying SHA-256 payload integrity.
     */
    public CompletableFuture<LoadResult> loadState(UUID uuid) {
        Supplier<LoadResult> loader = () -> {
            try {
                Optional<PersistedPlayerState> persistedOpt = repository.find(uuid);
                if (persistedOpt.isEmpty() || persistedOpt.get().payload() == null) {
                    PersistedPlayerState missing = persistedOpt.orElse(null);
                    if (missing != null && missing.revision() > 0) {
                        return recoverExactState(uuid, missing, null);
                    }
                    return new LoadResult.NotFound(persistedOpt.map(PersistedPlayerState::revision).orElse(0L));
                }

                PersistedPlayerState persisted = persistedOpt.get();
                byte[] rawPayload = persisted.payload();

                if (persisted.payloadHash() != null && !persisted.payloadHash().isEmpty()) {
                    String calculatedHash = DigestUtils.sha256(rawPayload);
                    if (!calculatedHash.equalsIgnoreCase(persisted.payloadHash())) {
                        LOGGER.error("[CORRUPTION] Payload checksum mismatch for player {}! Expected={}, Actual={}",
                            uuid, persisted.payloadHash(), calculatedHash);
                        return recoverExactState(uuid, persisted, rawPayload);
                    }
                }

                PlayerStateSnapshot rawSnapshot = serializer.deserialize(rawPayload);

                // History (especially SUPERSEDED attempts) cannot override a valid committed row.
                // Avoid an extra SQL query and possible LONGBLOB download on every login.
                if (!uuid.equals(rawSnapshot.playerUuid()) || rawSnapshot.revision() > persisted.revision()) {
                    return new LoadResult.Corrupted("Snapshot identity or revision disagrees with SQL", rawPayload);
                }
                long authoritativeRevision = persisted.revision();
                PlayerStateSnapshot snapshot = (rawSnapshot.revision() != authoritativeRevision)
                    ? new PlayerStateSnapshot(
                        rawSnapshot.playerUuid(),
                        authoritativeRevision,
                        rawSnapshot.fencingToken(),
                        rawSnapshot.inventory(),
                        rawSnapshot.enderChest(),
                        rawSnapshot.experience(),
                        rawSnapshot.vitals(),
                        rawSnapshot.hunger(),
                        rawSnapshot.effects(),
                        rawSnapshot.gameMode(),
                        rawSnapshot.flight(),
                        rawSnapshot.location(),
                        rawSnapshot.persistentData(),
                        rawSnapshot.statistics(),
                        rawSnapshot.customModuleData(),
                        rawSnapshot.capturedAt(),
                        rawSnapshot.schemaVersion()
                    )
                    : rawSnapshot;

                if (metricsService != null) {
                    metricsService.recordLoad();
                }
                return new LoadResult.Success(snapshot);
            } catch (SQLException e) {
                LOGGER.error("SQL error while loading state for {}: {}", uuid, e.getMessage(), e);
                return new LoadResult.Failed(e);
            } catch (Throwable t) {
                LOGGER.error("Error while loading or deserializing state for {}: {}", uuid, t.getMessage(), t);
                return new LoadResult.Failed(t);
            }
        };

        return operationQueue.submit(uuid, () ->
            workerExecutor != null
                ? CompletableFuture.supplyAsync(loader, workerExecutor)
                : CompletableFuture.supplyAsync(loader)
        );
    }

    private LoadResult recoverExactState(UUID uuid, PersistedPlayerState persisted, byte[] corruptPayload) throws Exception {
        if (repository.getSnapshotRepository() != null) {
            var exact = repository.getSnapshotRepository().findExactCommittedSnapshot(uuid, persisted.revision(), persisted.payloadHash());
            if (exact.isPresent()) {
                PlayerStateSnapshot restored = serializer.deserialize(exact.get().payload());
                if (uuid.equals(restored.playerUuid()) && restored.revision() <= persisted.revision()) {
                    if (metricsService != null) metricsService.recordLoad();
                    return new LoadResult.Success(withRevision(restored, persisted.revision()));
                }
            }
        }
        return new LoadResult.Corrupted("Committed snapshot unavailable; administrator review required", corruptPayload);
    }

    /**
     * Captures, serializes, and commits state for an active player.
     */
    private boolean canCapture(ActivePlayerSession session, SyncReason reason) {
        if (session == null || session.hasFailedLoad()
            || sessionService.getLocalSession(session.getPlayerUuid()).orElse(null) != session) return false;
        return session.getState() == SessionState.ACTIVE
            || (reason == SyncReason.QUIT && session.getState() == SessionState.SAVING);
    }

    public CompletableFuture<CommitResult> saveState(UUID uuid, SyncReason reason, boolean force) {
        ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
        if (!canCapture(session, reason)) {
            return CompletableFuture.completedFuture(new CommitResult.Failed(
                new IllegalStateException("Cannot save player " + uuid + ": session is not safe for capture")
            ));
        }

        return operationQueue.submit(uuid, () -> {
            ActivePlayerSession queuedSession = sessionService.getLocalSession(uuid).orElse(null);
            if (queuedSession != session || !canCapture(session, reason)) {
                return CompletableFuture.completedFuture(new CommitResult.Failed(
                    new IllegalStateException("Player session changed before queued save began")
                ));
            }
            if (!force && !session.isDirty()) {
                return CompletableFuture.completedFuture(new CommitResult.Success(session.getRevision(), 0));
            }

            // Read all optimistic-concurrency values only after earlier operations for this
            // player have completed. Reading them before queueing produces stale revisions.
            long capturedGeneration = session.getDirtyGeneration();
            long currentRev = session.getRevision();
            long token = session.getFencingToken();
            UUID sessionId = session.getSessionId();
            CompletableFuture<CommitResult> commitFuture = new CompletableFuture<>();

            synchronized (captureLock) {
                if (shuttingDown) {
                    return CompletableFuture.completedFuture(new CommitResult.Failed(
                        new IllegalStateException("Server is shutting down")));
                }
                pendingCaptures.add(commitFuture);
            }

            // Resolve the Bukkit entity on the global scheduler, then capture on the
            // entity scheduler. The operation queue itself runs on a worker thread.
            try {
                scheduler.runGlobal(() -> {
                    if (commitFuture.isDone()) {
                        return;
                    }
                    try {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) {
                            commitFuture.complete(new CommitResult.Failed(
                            new IllegalStateException("Player " + uuid + " is not online for state capture")
                            ));
                            return;
                        }

                        scheduler.runForPlayer(player, () -> {
                            synchronized (captureLock) {
                                if (commitFuture.isDone()) {
                                    return;
                                }
                                // Claim this capture before leaving the short lifecycle lock.
                                // Shutdown cancels only waiting captures. This running capture
                                // retains its per-player queue slot through commit completion.
                                pendingCaptures.remove(commitFuture);
                            }
                            try {
                                if (!canCapture(session, reason)) {
                                    commitFuture.complete(new CommitResult.Failed(new IllegalStateException("Session became unsafe before capture")));
                                    return;
                                }
                                Bukkit.getPluginManager().callEvent(new FriendlySyncPreSaveEvent(player, reason, currentRev));
                                if (!canCapture(session, reason)) {
                                    commitFuture.complete(new CommitResult.Failed(new IllegalStateException("Session became unsafe during pre-save event")));
                                    return;
                                }

                                PlayerStateSnapshot snapshot = syncModuleManager.captureSnapshot(player, currentRev, token, reason, session);

                                // Serialize & commit asynchronously on bounded worker pool
                                Runnable commitTask = () -> {
                                    try {
                                        if (reason == SyncReason.CHECKPOINT && session.getLastCommittedSnapshot() != null
                                            && snapshot.hasIdenticalState(session.getLastCommittedSnapshot())) {
                                            session.recordCommittedGeneration(capturedGeneration);
                                            LOGGER.debug("[SYNC] uuid={} checkpoint skipped: identical state to last commit (gen={})",
                                                uuid, capturedGeneration);
                                            commitFuture.complete(new CommitResult.Success(currentRev, 0));
                                            return;
                                        }

                                        byte[] serialized = serializer.serialize(snapshot);
                                        String hash = DigestUtils.sha256(serialized);

                                         CommitResult result = repository.commit(
                                            uuid,
                                            sessionId,
                                            token,
                                            currentRev,
                                            serialized,
                                            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION,
                                            hash,
                                            reason != null ? reason.name() : "SYNC",
                                            session.getServerId()
                                         );
                                         if (result == null) {
                                             result = repository.commit(
                                                 uuid,
                                                 sessionId,
                                                 token,
                                                 currentRev,
                                                 serialized,
                                                 PlayerStateSnapshot.CURRENT_SCHEMA_VERSION,
                                                 hash
                                             );
                                         }

                                        if (result instanceof CommitResult.Success success) {
                                            session.setRevision(success.newRevision());
                                            session.recordCommittedGeneration(capturedGeneration);
                                            session.setLastCommittedSnapshot(snapshot);
                                            // Capture already cached items on the entity thread. Re-caching
                                            // here would clear mutations made while SQL was in flight.
                                            if (metricsService != null) {
                                                metricsService.recordSave(success.durationMs());
                                            }
                                            try {
                                                Bukkit.getPluginManager().callEvent(
                                                new FriendlySyncPostSaveEvent(player, reason, success.newRevision(), success.durationMs())
                                                );
                                            } catch (Throwable ignored) {
                                            }
                                            LOGGER.debug("[SYNC] uuid={} revision={} server={} save=success duration={}ms reason={}",
                                            uuid, success.newRevision(), session.getServerId(), success.durationMs(), reason);
                                        } else if (result instanceof CommitResult.Conflict conflict) {
                                            if (conflict.fencingToken() == session.getFencingToken() && conflict.revision() > session.getRevision()) {
                                                LOGGER.info("[SAVE] Reconciling session revision from {} to {} for {}",
                                                    session.getRevision(), conflict.revision(), session.getUsername());
                                                session.setRevision(conflict.revision());
                                            }
                                        }

                                        commitFuture.complete(result);
                                    } catch (Throwable e) {
                                        LOGGER.error("Async error during state commit for {}: {}", uuid, e.getMessage(), e);
                                        commitFuture.complete(new CommitResult.Failed(e));
                                    }
                                };

                                if (workerExecutor != null) {
                                    workerExecutor.execute(commitTask);
                                } else {
                                    scheduler.runAsync(commitTask);
                                }
                            } catch (Throwable e) {
                                LOGGER.error("Scheduler error during state capture for {}: {}", uuid, e.getMessage(), e);
                                commitFuture.complete(new CommitResult.Failed(e));
                            }
                        }, () -> commitFuture.complete(new CommitResult.Failed(
                        new IllegalStateException("Player " + uuid + " retired before state capture")
                        )));
                    } catch (Throwable error) {
                        commitFuture.complete(new CommitResult.Failed(error));
                    }
                });

            } catch (Throwable error) {
                commitFuture.complete(new CommitResult.Failed(error));
            }
            commitFuture.whenComplete((result, error) -> {
                synchronized (captureLock) {
                    pendingCaptures.remove(commitFuture);
                }
            });
            return commitFuture;
        });
    }

    /**
     * Atomically commits a prepared snapshot directly.
     */
    public CompletableFuture<CommitResult> commitDirect(PlayerStateSnapshot snapshot, SessionIdentity session) {
        return operationQueue.submit(session.playerUuid(), () ->
            CompletableFuture.completedFuture(commitFinalSnapshotInQueue(snapshot, session)))
            .handleAsync((result, error) -> {
                if (error == null) return result;
                Throwable cause = error;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof java.util.concurrent.RejectedExecutionException
                        && session.playerUuid().equals(snapshot.playerUuid())
                        && session.fencingToken() == snapshot.fencingToken()) {
                    retainRejectedFinalSave(snapshot, session);
                    LOGGER.error("Final save admission rejected for {}; capture retained for journal recovery", session.playerUuid());
                }
                return new CommitResult.Failed(cause);
            }); // Complete off the tick thread, including admission failures; quit handlers can safely backpressure.
    }

    /** Retain an immutable final capture when the worker cannot admit its commit. */
    public void retainRejectedFinalSave(PlayerStateSnapshot snapshot, SessionIdentity session) {
        rejectedFinalSaves.putIfAbsent(session.playerUuid(), new RejectedFinalSave(snapshot, session));
    }

    /** Called only in the player's serial worker slot; never rebase a rejected capture. */
    private void journalRejectedFinalSave(UUID uuid) throws Exception {
        RejectedFinalSave rejected = rejectedFinalSaves.get(uuid);
        if (rejected == null) return;
        if (finalSaveJournal == null) throw new IllegalStateException("Recovery journal is unavailable");
        // An older admitted write may already have a recovery record. Recover it
        // first; never overwrite an unresolved durable inventory with this capture.
        if (finalSaveJournal.contains(uuid) || unwrittenFinalSaves.containsKey(uuid)) return;
        byte[] payload = serializer.serialize(rejected.snapshot());
        FinalSaveJournal.Entry entry = new FinalSaveJournal.Entry(rejected.identity(), rejected.snapshot().revision(),
            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION, payload, DigestUtils.sha256(payload));
        // Retain the original fencing identity and revision. A concurrent committed
        // checkpoint becomes a conflict for review, never an inventory overwrite.
        finalSaveJournal.store(entry);
        rejectedFinalSaves.remove(uuid, rejected);
    }

    /** Runs inside an already-held player queue slot, including the pre-restore final save. */
    /** Final commit with journal recovery. Caller must hold this player's operation queue slot off-thread. */
    public CommitResult commitFinalSnapshotInQueue(PlayerStateSnapshot snapshot, SessionIdentity session) {
        try {
            ActivePlayerSession activeSession = sessionService.getLocalSession(session.playerUuid()).orElse(null);
            if (activeSession == null || activeSession.hasFailedLoad()
                || !activeSession.getSessionId().equals(session.sessionId())
                || activeSession.getFencingToken() != session.fencingToken()) {
                return new CommitResult.Failed(
                    new IllegalStateException("Player session changed before direct commit began")
                );
            }

            long expectedRevision = activeSession.getRevision();
            PlayerStateSnapshot commitSnapshot = withRevision(snapshot, expectedRevision);
            byte[] serialized = serializer.serialize(commitSnapshot);
            String hash = DigestUtils.sha256(serialized);

            CommitResult result;
            if (finalSaveJournal != null) {
                FinalSaveJournal.Entry entry = new FinalSaveJournal.Entry(session, expectedRevision,
                    PlayerStateSnapshot.CURRENT_SCHEMA_VERSION, serialized, hash);
                unwrittenFinalSaves.put(session.playerUuid(), entry);
                finalSaveJournal.store(entry);
                unwrittenFinalSaves.remove(session.playerUuid(), entry);
                result = finalSaveJournal.commit(entry);
                if (result instanceof CommitResult.Success) confirmedFinalSaves.put(session.playerUuid(), entry);
            } else {
                result = repository.commit(session.playerUuid(), session.sessionId(), session.fencingToken(),
                    expectedRevision, serialized, PlayerStateSnapshot.CURRENT_SCHEMA_VERSION, hash, "DIRECT", session.serverId());
                if (result == null) {
                    result = repository.commit(session.playerUuid(), session.sessionId(), session.fencingToken(),
                        expectedRevision, serialized, PlayerStateSnapshot.CURRENT_SCHEMA_VERSION, hash);
                }
            }
            if (result instanceof CommitResult.Success success) {
                activeSession.setRevision(success.newRevision());
                activeSession.setLastCommittedSnapshot(commitSnapshot);
                if (metricsService != null) {
                    metricsService.recordSave(success.durationMs());
                }
            } else if (result instanceof CommitResult.Conflict conflict) {
                if (conflict.fencingToken() == activeSession.getFencingToken() && conflict.revision() > activeSession.getRevision()) {
                    LOGGER.info("[DIRECT] Reconciling session revision from {} to {} for {}",
                        activeSession.getRevision(), conflict.revision(), activeSession.getUsername());
                    activeSession.setRevision(conflict.revision());
                }
            }
            return result;
        } catch (Throwable e) {
            LOGGER.error("Error during direct commit for {}: {}", session.playerUuid(), e.getMessage(), e);
            return new CommitResult.Failed(e);
        }
    }

    public CompletableFuture<String> restoreSnapshot(UUID uuid, String snapshotId) {
        if (shuttingDown) return CompletableFuture.completedFuture("Server is shutting down; restore refused.");
        return operationQueue.submit(uuid, () -> CompletableFuture.supplyAsync(() -> {
            if (shuttingDown) return CompletableFuture.completedFuture("Server is shutting down; restore refused.");
            var snapRepo = repository.getSnapshotRepository();
            if (snapRepo == null) {
                return CompletableFuture.completedFuture("Snapshot storage is disabled in config.yml.");
            }
            try {
                if (hasPendingFinalSave(uuid) || hasFinalSaveConflict(uuid)) {
                    return CompletableFuture.completedFuture("Resolve pending final saves before restoring this player.");
                }
                Optional<dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord> recordOpt;
                if (snapshotId == null || snapshotId.isBlank() || "latest".equalsIgnoreCase(snapshotId)) {
                    recordOpt = snapRepo.findHighestRevisionSnapshot(uuid);
                } else {
                    recordOpt = snapRepo.findSnapshotById(snapshotId);
                }
                if (recordOpt.isEmpty()) {
                    return CompletableFuture.completedFuture("No matching snapshot record found in MySQL.");
                }
                var record = recordOpt.get();
                if (!uuid.equals(record.playerUuid())) {
                    return CompletableFuture.completedFuture("Snapshot belongs to a different player; restore refused.");
                }
                byte[] payload = record.payload();
                if (payload == null || !DigestUtils.sha256(payload).equalsIgnoreCase(record.payloadHash())) {
                    return CompletableFuture.completedFuture("Snapshot payload is empty or corrupt; restore refused.");
                }
                PlayerStateSnapshot snapshot = serializer.deserialize(payload);
                if (!uuid.equals(snapshot.playerUuid())) {
                    return CompletableFuture.completedFuture("Snapshot payload belongs to a different player; restore refused.");
                }
                // Validate saved items before freezing anyone or changing durable state.
                syncModuleManager.preDecodeInventory(snapshot.inventory());
                syncModuleManager.preDecodeEnderChest(snapshot.enderChest());
                ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
                if (session == null) {
                    return CompletableFuture.completedFuture(restoreOffline(uuid, record.snapshotId(), snapshot));
                }
                if (session.getState() != SessionState.ACTIVE || session.hasFailedLoad()) {
                    return CompletableFuture.completedFuture("Player session is busy or unsafe; restore refused.");
                }

                CompletableFuture<String> result = new CompletableFuture<>();
                synchronized (captureLock) {
                    if (shuttingDown) return CompletableFuture.completedFuture("Server is shutting down; restore refused.");
                    pendingRestores.add(result);
                }
                result.whenComplete((message, error) -> {
                    synchronized (captureLock) { pendingRestores.remove(result); }
                });
                scheduler.runGlobal(() -> {
                    try {
                        if (result.isDone()) return;
                        Player player = Bukkit.getPlayer(uuid);
                        if (player == null || !player.isOnline()) {
                            result.complete("Player disconnected before restore; wait for their final save and retry.");
                            return;
                        }
                        scheduler.runForPlayer(player, () -> {
                            synchronized (captureLock) {
                                if (result.isDone()) return;
                                pendingRestores.remove(result);
                            }
                            if (sessionService.getLocalSessionRaw(uuid) != session || session.hasFailedLoad()
                                || !session.transitionTo(SessionState.FREEZING)) {
                                result.complete("Player session changed before restore; retry after their final save.");
                                return;
                            }
                            try {
                                player.closeInventory();
                                PlayerStateSnapshot original = syncModuleManager.captureSnapshot(player, session.getRevision(),
                                    session.getFencingToken(), SyncReason.QUIT, session);
                                CompletableFuture.supplyAsync(() -> {
                                    try {
                                        CommitResult saved = commitFinalSnapshotInQueue(original, session.toIdentity());
                                        if (!(saved instanceof CommitResult.Success)) {
                                            throw new IllegalStateException("Current player data could not be saved: " + saved);
                                        }
                                        // Release only after the original final save is confirmed. The offline
                                        // restore CAS rejects a destination that acquires ownership in between.
                                        if (!repository.releaseOwnership(uuid, session.getSessionId(), session.getFencingToken(), "OFFLINE")) {
                                            throw new IllegalStateException("Current player session could not be released safely");
                                        }
                                        session.forceState(SessionState.RELEASED);
                                        sessionService.removeSession(session.toIdentity());
                                        acknowledgeFinalSave(uuid);
                                        if (hasPendingFinalSave(uuid)) {
                                            throw new IllegalStateException("Final-save journal cleanup is pending; retry restore later");
                                        }
                                        return restoreOffline(uuid, record.snapshotId(), snapshot);
                                    } catch (Exception error) {
                                        session.forceState(SessionState.FAILED);
                                        if (!sessionService.isPlayerOnline(uuid) && hasPendingFinalSave(uuid)) {
                                            sessionService.removeSession(session.toIdentity());
                                        }
                                        return "Restore refused: " + error.getMessage();
                                    }
                                }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool())
                                    .whenComplete((message, error) -> {
                                        if (error != null) session.forceState(SessionState.FAILED);
                                        try {
                                            scheduler.runForPlayer(player, () -> player.kick(Component.text(
                                                "An administrator processed a profile restore. Please reconnect to load your saved data.", NamedTextColor.YELLOW)));
                                        } catch (RuntimeException schedulingError) {
                                            LOGGER.warn("Could not disconnect locked player {} after restore", uuid, schedulingError);
                                        } finally {
                                            if (error != null) result.completeExceptionally(error);
                                            else result.complete(message);
                                        }
                                    });
                            } catch (Throwable error) {
                                session.transitionTo(SessionState.ACTIVE);
                                result.complete("Restore refused before saving: " + error.getMessage());
                            }
                        }, () -> result.complete("Player retired before restore; wait for their final save and retry."));
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
                return result;

            } catch (Exception e) {
                LOGGER.error("Failed to restore snapshot for {}", uuid, e);
                return CompletableFuture.completedFuture("Failed to restore snapshot: " + e.getMessage());
            }
        }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool()).thenCompose(result -> result));
    }

    private String restoreOffline(UUID uuid, String snapshotId, PlayerStateSnapshot snapshot) throws SQLException {
        PersistedPlayerState current = repository.find(uuid).orElse(null);
        if (current == null || current.ownerServer() != null
            || !("OFFLINE".equals(current.sessionState()) || "RELEASED".equals(current.sessionState()))) {
            return "Player has no offline profile or is owned by another session; restore refused.";
        }
        // Legacy snapshots contain no statistics. Preserve current SQL statistics when available.
        var statistics = snapshot.schemaVersion() < 3 && current.payload() != null
            ? serializer.deserialize(current.payload()).statistics() : snapshot.statistics();
        PlayerStateSnapshot replacement = new PlayerStateSnapshot(uuid, current.revision(),
            Math.addExact(current.fencingToken(), 1), snapshot.inventory(), snapshot.enderChest(), snapshot.experience(),
            snapshot.vitals(), snapshot.hunger(), snapshot.effects(), snapshot.gameMode(), snapshot.flight(),
            snapshot.location(), snapshot.persistentData(), statistics, snapshot.customModuleData(),
            java.time.Instant.now(), PlayerStateSnapshot.CURRENT_SCHEMA_VERSION);
        byte[] payload = serializer.serialize(replacement);
        CommitResult restored = repository.restoreSnapshot(current, payload,
            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION, DigestUtils.sha256(payload));
        if (restored instanceof CommitResult.Success success) {
            return "Restored snapshot " + snapshotId + " at revision " + success.newRevision()
                + ". Previous SQL data is backed up in snapshot history. Reconnect to load the restored profile.";
        }
        return "Restore commit returned conflict or failure: " + restored;
    }

    private static PlayerStateSnapshot withRevision(PlayerStateSnapshot snapshot, long revision) {
        if (snapshot.revision() == revision) {
            return snapshot;
        }
        return new PlayerStateSnapshot(
            snapshot.playerUuid(), revision, snapshot.fencingToken(), snapshot.inventory(),
            snapshot.enderChest(), snapshot.experience(), snapshot.vitals(), snapshot.hunger(),
            snapshot.effects(), snapshot.gameMode(), snapshot.flight(), snapshot.location(),
            snapshot.persistentData(), snapshot.statistics(), snapshot.customModuleData(),
            snapshot.capturedAt(), snapshot.schemaVersion()
        );
    }
}
