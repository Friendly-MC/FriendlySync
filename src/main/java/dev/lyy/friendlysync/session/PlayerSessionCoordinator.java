package dev.lyy.friendlysync.session;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.lyy.friendlysync.api.event.FriendlySyncPostLoadEvent;
import dev.lyy.friendlysync.api.event.FriendlySyncPreLoadEvent;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates player lifecycle transitions across login, join, load, activation, quit, and disconnect.
 */
public final class PlayerSessionCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSessionCoordinator.class);

    private final PlayerOwnershipService ownershipService;
    private final PlayerSessionService sessionService;
    private final PlayerStateService stateService;
    private final SyncModuleManager syncModuleManager;
    private final JoinLockService joinLockService;
    private final PlatformScheduler scheduler;
    private final DirtyTrackingService dirtyTrackingService;
    private final dev.lyy.friendlysync.messaging.MessagingService messagingService;
    private final dev.lyy.friendlysync.lock.TransferFreezeService freezeService;

    public record PendingSnapshot(
        SessionIdentity identity,
        PlayerStateSnapshot snapshot,
        dev.lyy.friendlysync.sync.module.InventorySyncModule.DecodedInventory decodedInventory,
        org.bukkit.inventory.ItemStack[] decodedEnderChest,
        Instant createdAt
    ) {
        public PendingSnapshot(SessionIdentity identity, PlayerStateSnapshot snapshot) {
            this(identity, snapshot, null, null, Instant.now());
        }

        public PendingSnapshot(
            SessionIdentity identity,
            PlayerStateSnapshot snapshot,
            dev.lyy.friendlysync.sync.module.InventorySyncModule.DecodedInventory decodedInventory,
            org.bukkit.inventory.ItemStack[] decodedEnderChest
        ) {
            this(identity, snapshot, decodedInventory, decodedEnderChest, Instant.now());
        }
    }

    private dev.lyy.friendlysync.transfer.PlayerTransferService outgoingTransfers;

    public void configureOutgoingTransfers(dev.lyy.friendlysync.transfer.PlayerTransferService transfers) {
        this.outgoingTransfers = transfers;
    }

    private volatile boolean draining;
    private final ExecutorService workerExecutor;
    private final java.util.Set<CompletableFuture<?>> shutdownSaves = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingSnapshot> pendingSnapshots = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> handoffsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<CommitResult>> activeQuitSaves = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> transferRetryCounts = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<dev.lyy.friendlysync.messaging.message.TransferReadyMessage>> pendingTransfers = new ConcurrentHashMap<>();

    public PlayerSessionCoordinator(
        PlayerOwnershipService ownershipService,
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        SyncModuleManager syncModuleManager,
        JoinLockService joinLockService,
        PlatformScheduler scheduler,
        DirtyTrackingService dirtyTrackingService
    ) {
        this(ownershipService, sessionService, stateService, syncModuleManager, joinLockService, scheduler, dirtyTrackingService, null, null, null);
    }

    public PlayerSessionCoordinator(
        PlayerOwnershipService ownershipService,
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        SyncModuleManager syncModuleManager,
        JoinLockService joinLockService,
        PlatformScheduler scheduler,
        DirtyTrackingService dirtyTrackingService,
        dev.lyy.friendlysync.messaging.MessagingService messagingService,
        dev.lyy.friendlysync.lock.TransferFreezeService freezeService
    ) {
        this(ownershipService, sessionService, stateService, syncModuleManager, joinLockService, scheduler, dirtyTrackingService, messagingService, freezeService, null);
    }

    public PlayerSessionCoordinator(
        PlayerOwnershipService ownershipService,
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        SyncModuleManager syncModuleManager,
        JoinLockService joinLockService,
        PlatformScheduler scheduler,
        DirtyTrackingService dirtyTrackingService,
        dev.lyy.friendlysync.messaging.MessagingService messagingService,
        dev.lyy.friendlysync.lock.TransferFreezeService freezeService,
        ExecutorService workerExecutor
    ) {
        this.ownershipService = Preconditions.checkNotNull(ownershipService, "ownershipService cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.stateService = Preconditions.checkNotNull(stateService, "stateService cannot be null");
        this.syncModuleManager = Preconditions.checkNotNull(syncModuleManager, "syncModuleManager cannot be null");
        this.joinLockService = Preconditions.checkNotNull(joinLockService, "joinLockService cannot be null");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler cannot be null");
        this.dirtyTrackingService = dirtyTrackingService;
        this.messagingService = messagingService;
        this.freezeService = freezeService;
        this.workerExecutor = workerExecutor;

        if (messagingService != null) {
            messagingService.registerTransferRequestListener(this::handleIncomingTransferRequest);
            messagingService.registerTransferListener(this::handleIncomingTransferReady);
        }

        this.ownershipService.setOnSessionReleased((PlayerOwnershipService.SessionReleaseListener) (uuid, releasingSession) -> {
            stateService.acknowledgeFinalSave(uuid);
            if (releasingSession != null) {
                pendingSnapshots.computeIfPresent(uuid, (k, pending) -> {
                    if (pending.identity() == null
                        || pending.identity().sessionId().equals(releasingSession.sessionId())
                        || pending.identity().fencingToken() == releasingSession.fencingToken()) {
                        return null; // remove only matching releasing session
                    }
                    return pending; // preserve incoming player state from reconnect/hop
                });
            } else {
                pendingSnapshots.remove(uuid);
            }
            CompletableFuture<dev.lyy.friendlysync.messaging.message.TransferReadyMessage> pendingTransfer = pendingTransfers.remove(uuid);
            if (pendingTransfer != null) {
                pendingTransfer.complete(null);
            }
            joinLockService.remove(uuid);
            if (this.dirtyTrackingService != null) {
                this.dirtyTrackingService.clear(uuid);
            }
            dev.lyy.friendlysync.listener.PlayerDeathListener.clear(uuid);
        });
    }

    private void handleIncomingTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage message) {
        if (message == null || message.playerUuid() == null) {
            return;
        }
        String myServer = ownershipService.getServerId();
        if (message.targetServer() == null || message.targetServer().isBlank() || message.targetServer().equalsIgnoreCase(myServer)) {
            CompletableFuture<dev.lyy.friendlysync.messaging.message.TransferReadyMessage> future = pendingTransfers.get(message.playerUuid());
            if (future != null) {
                LOGGER.debug("[HANDOFF] Received transfer ready message for player {} from {}",
                    message.playerUuid(), message.sourceServer());
                future.complete(message);
            }
        }
    }

    /**
     * Handles asynchronous pre-login: acquires exclusive ownership and loads committed state from SQL.
     */
    public void handleAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        // Share the existing 15-second load budget with handoff waiting, rather than stacking waits.
        long loginDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        UUID uuid = event.getUniqueId();
        String username = event.getName();

        if (draining) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("This server is preparing to stop. Please use another server or contact an administrator.", NamedTextColor.YELLOW));
            return;
        }
        if (stateService.hasPendingFinalSave(uuid) || stateService.hasFinalSaveConflict(uuid)) {
            try {
                CompletableFuture<CommitResult> flush = stateService.flushPendingFinalSave(uuid);
                if (flush != null) {
                    flush.get(2, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
            }
        }
        if (stateService.hasFinalSaveConflict(uuid)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Your saved data needs administrator review. Reconnecting will not resolve this. Ask staff to check /friendlysync snapshots " + username, NamedTextColor.YELLOW));
            return;
        }
        if (stateService.hasPendingFinalSave(uuid)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Your last save is synchronizing to database. Please reconnect in a moment.", NamedTextColor.YELLOW));
            return;
        }
        OwnershipResult ownershipResult = ownershipService.acquireOwnership(uuid, username);

        // If the player is reported as already owned, notify the owning server via Redis
        // so it flushes the player's snapshot and releases ownership while the player is still
        // online during Velocity proxy server-switch.
        if (ownershipResult instanceof OwnershipResult.AlreadyOwned owned) {
            CompletableFuture<dev.lyy.friendlysync.messaging.message.TransferReadyMessage> readyFuture = new CompletableFuture<>();
            pendingTransfers.put(uuid, readyFuture);
            try {
                boolean isLocalOwner = owned.ownerServer() != null && owned.ownerServer().equalsIgnoreCase(ownershipService.getServerId());
                if (isLocalOwner) {
                    CompletableFuture<CommitResult> localSave = activeQuitSaves.get(uuid);
                    if (localSave != null) {
                        localSave.whenComplete((res, err) -> readyFuture.complete(null));
                    } else if (sessionService.getLocalSession(uuid).isEmpty()
                            && !stateService.hasPendingFinalSave(uuid)
                            && !stateService.hasFinalSaveConflict(uuid)) {
                        if (ownershipService.releaseOwnershipIfOwner(uuid, "OFFLINE")) {
                            readyFuture.complete(null);
                        }
                    }
                } else if (messagingService != null && owned.ownerServer() != null && !owned.ownerServer().isBlank()) {
                    LOGGER.debug("[PRE-LOGIN] Player {} already owned by {}. Requesting handoff snapshot via Redis...",
                        username, owned.ownerServer());
                    try {
                        messagingService.publishTransferRequest(
                            dev.lyy.friendlysync.messaging.message.TransferRequestMessage.create(
                                uuid, ownershipService.getServerId(), owned.ownerServer()
                            )
                        );
                    } catch (RuntimeException e) {
                        LOGGER.warn("[PRE-LOGIN] Handoff notification failed for {}; checking SQL release", username, e);
                    }
                }

                if (owned.ownerServer() != null && !owned.ownerServer().isBlank()) {
                    ownershipResult = awaitHandoff(uuid, username, ownershipResult, readyFuture, loginDeadline);
                } else {
                    ownershipResult = ownershipService.acquireOwnership(uuid, username);
                }
            } finally {
                pendingTransfers.remove(uuid, readyFuture);
            }
        }

        if (ownershipResult instanceof OwnershipResult.AlreadyOwned owned && !draining
            && ownershipService.releaseAbandonedLease(uuid, owned.ownerServer(), owned.leaseExpiresAt())) {
            ownershipResult = ownershipService.acquireOwnership(uuid, username);
        }

        if (ownershipResult instanceof OwnershipResult.AlreadyOwned owned) {
            String ownerName =owned.ownerServer() != null && !owned.ownerServer().isBlank() ? owned.ownerServer() : "another server";
            LOGGER.warn("[PRE-LOGIN] Rejecting {}: owned by {} until {} (state={})", username, ownerName, owned.leaseExpiresAt(), owned.sessionState());
            boolean needsRecovery = owned.leaseExpiresAt() != null && owned.leaseExpiresAt().isBefore(Instant.now())
                && !"ACQUIRING".equals(owned.sessionState()) && !"LOADING".equals(owned.sessionState());
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text(needsRecovery
                    ? "Your session on " + ownerName + " needs recovery. Ask staff to review your saved data."
                    : "Your player data is still synchronizing from " + ownerName + ". Please reconnect in a moment.", NamedTextColor.YELLOW)
            );
            return;
        }

        if (ownershipResult instanceof OwnershipResult.DatabaseUnavailable unavailable) {
            LOGGER.error("[PRE-LOGIN] Rejecting {}: database is unavailable", username, unavailable.cause());
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Database connectivity issue. Please try again shortly.", NamedTextColor.RED)
            );
            return;
        }

        if (ownershipResult instanceof OwnershipResult.Conflict conflict) {
            LOGGER.error("[PRE-LOGIN] Rejecting {}: ownership conflict: {}", username, conflict.reason());
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Session conflict detected. Please wait a few seconds before reconnecting.", NamedTextColor.RED)
            );
            return;
        }

        if (!(ownershipResult instanceof OwnershipResult.Acquired acquired)) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Unable to acquire player session.", NamedTextColor.RED)
            );
            return;
        }

        // Ownership acquired. Load durable state.
        try {
            long remainingNanos = loginDeadline - System.nanoTime();
            if (remainingNanos <= 0 || Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.TimeoutException("Pre-login budget exhausted before loading");
            }
            LoadResult loadResult = stateService.loadState(uuid).get(remainingNanos, TimeUnit.NANOSECONDS);
            if ((loadResult instanceof LoadResult.Success || loadResult instanceof LoadResult.NotFound)
                && (draining || !ownershipService.updateSessionState(acquired.session(), "LOADING"))) {
                ownershipService.releaseOwnership(acquired.session(), "OFFLINE");
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Could not prepare a durable player session. Please reconnect.", NamedTextColor.RED));
                return;
            }
            if (System.nanoTime() >= loginDeadline) {
                throw new java.util.concurrent.TimeoutException("Pre-login budget exhausted during session preparation");
            }

            if (loadResult instanceof LoadResult.Success success) {
                var decodedInv = syncModuleManager.preDecodeInventory(success.snapshot().inventory());
                var decodedEc = syncModuleManager.preDecodeEnderChest(success.snapshot().enderChest());
                if (System.nanoTime() >= loginDeadline) {
                    throw new java.util.concurrent.TimeoutException("Pre-login budget exhausted while decoding state");
                }
                pendingSnapshots.put(uuid, new PendingSnapshot(acquired.session(), success.snapshot(), decodedInv, decodedEc));
                ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
                if (session != null) {
                    session.setRevision(Math.max(session.getRevision(), success.snapshot().revision()));
                    session.transitionTo(SessionState.LOADING);
                    if (success.snapshot().enderChest() != null) {
                        session.cacheEnderChest(success.snapshot().enderChest());
                    }
                    session.setLastCommittedSnapshot(success.snapshot());
                }
            } else if (loadResult instanceof LoadResult.NotFound) {
                ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
                if (session != null) {
                    session.transitionTo(SessionState.LOADING);
                }
            } else if (loadResult instanceof LoadResult.Corrupted corrupted) {
                LOGGER.error("[PRE-LOGIN] Rejecting {}: corrupted data snapshot! Reason: {}", username, corrupted.reason());
                ownershipService.releaseOwnership(acquired.session(), "FAILED");
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Your player profile is flagged as corrupted. Please contact an administrator.", NamedTextColor.RED)
                );
            } else if (loadResult instanceof LoadResult.Failed failed) {
                LOGGER.error("[PRE-LOGIN] Rejecting {}: failed to load state: {}", username, failed.cause().getMessage(), failed.cause());
                ownershipService.releaseOwnership(acquired.session(), "FAILED");
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Failed to load your synchronized data. Please reconnect.", NamedTextColor.RED)
                );
            }
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) ? t.getCause() : t;
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                LOGGER.error("[PRE-LOGIN] Synchronization deadline exceeded for {} (handoff, database or worker stalled)", username);
            } else {
                LOGGER.error("[PRE-LOGIN] Timed out or failed loading state for {}: {}", username, cause.getMessage(), cause);
            }
            ownershipService.releaseOwnership(acquired.session(), "FAILED");
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Session synchronization timed out or failed. Please try again.", NamedTextColor.RED)
            );
        }
    }

    private OwnershipResult awaitHandoff(UUID uuid, String username, OwnershipResult result,
        CompletableFuture<dev.lyy.friendlysync.messaging.message.TransferReadyMessage> ready, long loginDeadline) {
        long deadline = Math.min(loginDeadline, System.nanoTime() + ownershipService.getAcquisitionWaitLimit().toNanos());
        long delay = TimeUnit.MILLISECONDS.toNanos(100);
        boolean signalHandled = false;
        while (result instanceof OwnershipResult.AlreadyOwned && !draining && System.nanoTime() < deadline) {
            boolean notified = false;
            try {
                long wait = Math.min(delay, deadline - System.nanoTime());
                if (wait <= 0) break;
                if (signalHandled) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                } else {
                    ready.get(wait, TimeUnit.NANOSECONDS);
                    signalHandled = true;
                    notified = true;
                }
            } catch (java.util.concurrent.TimeoutException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
                signalHandled = true;
            }
            if (draining || System.nanoTime() >= loginDeadline) return result;
            try {
                // Back off indexed metadata reads; only take SQL write locks when a release is observed.
                if (notified || ownershipService.isOwnershipReleased(uuid)) {
                    result = ownershipService.acquireOwnership(uuid, username);
                }
            } catch (java.sql.SQLException e) {
                return new OwnershipResult.DatabaseUnavailable(e);
            }
            delay = Math.min(delay * 2, TimeUnit.SECONDS.toNanos(1));
        }
        // One final fenced attempt also handles a pre-login lease that expired while waiting.
        return result instanceof OwnershipResult.AlreadyOwned && !draining && System.nanoTime() < loginDeadline
            ? ownershipService.acquireOwnership(uuid, username) : result;
    }

    /**
     * Handles player join: applies state snapshot and activates session.
     */
    public void handlePlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        sessionService.markPlayerOnline(uuid);
        joinLockService.setExplicitlyLocked(uuid, true);

        ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
        if (session == null) {
            LOGGER.warn("[JOIN] Session registration missing for {} ({}); deferring kick by 1 tick to prevent ChunkMap entity corruption",
                player.getName(), uuid);
            scheduler.runForPlayerLater(player, Duration.ofMillis(50), () -> {
                if (player.isOnline()) {
                    player.kick(Component.text("Session registration missing. Please reconnect.", NamedTextColor.RED));
                }
            });
            return;
        }

        PendingSnapshot pending = pendingSnapshots.remove(uuid);
        PlayerStateSnapshot snapshot = pending != null ? pending.snapshot() : null;
        var decodedInv = pending != null ? pending.decodedInventory() : null;
        var decodedEc = pending != null ? pending.decodedEnderChest() : null;
        activateLocally(player, session, snapshot, decodedInv, decodedEc);
    }

    private void activateLocally(
        Player player,
        ActivePlayerSession session,
        PlayerStateSnapshot snapshot,
        dev.lyy.friendlysync.sync.module.InventorySyncModule.DecodedInventory decodedInventory,
        org.bukkit.inventory.ItemStack[] decodedEnderChest
    ) {
        // Unjoined handshakes remain recoverable. Confirm gameplay ownership only after join,
        // before applying state or allowing interaction, without blocking the entity thread.
        scheduler.runAsync(() -> {
            UUID uuid = session.getPlayerUuid();
            if (draining || !sessionService.isPlayerOnline(uuid)
                || sessionService.getLocalSession(uuid).orElse(null) != session
                || session.getState() != SessionState.LOADING) return;
            boolean confirmed = session.getFencingToken() <= 0
                || ownershipService.updateSessionState(session.toIdentity(), "ACTIVE");
            scheduler.runForPlayer(player, () -> {
                if (draining || !player.isOnline()
                    || sessionService.getLocalSession(uuid).orElse(null) != session
                    || session.getState() != SessionState.LOADING) return;
                if (!confirmed) {
                    session.markLoadFailed();
                    session.forceState(SessionState.FAILED);
                    player.kick(Component.text("Could not activate your player session safely. Please reconnect.", NamedTextColor.RED));
                    return;
                }
                applySnapshotAndActivate(player, session, snapshot, decodedInventory, decodedEnderChest);
            });
        });
    }

    private void applySnapshotAndActivate(
        Player player,
        ActivePlayerSession session,
        PlayerStateSnapshot snapshot,
        dev.lyy.friendlysync.sync.module.InventorySyncModule.DecodedInventory decodedInventory,
        org.bukkit.inventory.ItemStack[] decodedEnderChest
    ) {
        if (draining) {
            player.kick(Component.text("Server is shutting down. Please reconnect later."));
            return;
        }
        try {
            if (snapshot != null) {
                Bukkit.getPluginManager().callEvent(new FriendlySyncPreLoadEvent(player, snapshot.revision()));
                syncModuleManager.applySnapshot(player, snapshot, session, decodedInventory, decodedEnderChest);
                Bukkit.getPluginManager().callEvent(new FriendlySyncPostLoadEvent(player, snapshot.revision()));
                session.setLastCommittedSnapshot(snapshot);
            }
            activatePlayer(player, session);
        } catch (Exception e) {
            LOGGER.error("Error applying player state snapshot for {}: {}", player.getName(), e.getMessage(), e);
            session.markLoadFailed();
            session.forceState(SessionState.FAILED);
            player.kick(Component.text("Your saved items could not be loaded safely. Please contact an administrator.", NamedTextColor.RED));
        }
    }

    private void activatePlayer(Player player, ActivePlayerSession session) {
        try {
            session.transitionTo(SessionState.ACTIVE);
            joinLockService.remove(player.getUniqueId());
            LOGGER.debug("[ACTIVE] Player {} (uuid={}) activated at revision {}",
                player.getName(), player.getUniqueId(), session.getRevision());
        } catch (Exception e) {
            LOGGER.error("Error applying player state snapshot for {}: {}", player.getName(), e.getMessage(), e);
            session.markLoadFailed();
            session.forceState(SessionState.FAILED);
            player.kick(Component.text("Your saved items could not be loaded safely. Please contact an administrator.", NamedTextColor.RED));
        }
    }

    /**
     * Handles disallowance during login (e.g. kicked, cancelled, or handshake failure before join).
     * Cleans up pending snapshots and releases any acquired database ownership immediately.
     */
    public void handleLoginDisallowed(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
        // A rejected duplicate login must not tear down gameplay or an outstanding final save.
        if (sessionService.isPlayerOnline(uuid) || (session != null
            && session.getState() != SessionState.ACQUIRING && session.getState() != SessionState.LOADING)) {
            return;
        }
        sessionService.markPlayerOffline(uuid);
        pendingSnapshots.remove(uuid);
        joinLockService.remove(uuid);
        if (messagingService != null) {
            messagingService.clearTransferPayload(uuid);
        }
        if (dirtyTrackingService != null) {
            dirtyTrackingService.clear(uuid);
        }
        dev.lyy.friendlysync.listener.PlayerDeathListener.clear(uuid);
        if (session != null && session.getFencingToken() > 0) {
            SessionIdentity identity = session.toIdentity();
            sessionService.removeSession(identity);
            scheduler.runAsync(() -> ownershipService.releaseOwnership(identity, "OFFLINE"));
        }
    }

    /**
     * Prunes orphaned pending snapshots for players whose handshake was abandoned before joining.
     */
    public void pruneStalePendingSnapshots() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(30));
        pendingSnapshots.entrySet().removeIf(entry -> {
            PendingSnapshot pending = entry.getValue();
            if (pending.createdAt() != null && pending.createdAt().isBefore(cutoff)) {
                UUID uuid = entry.getKey();
                if (!sessionService.isPlayerOnline(uuid)) {
                    LOGGER.debug("[HANDSHAKE] Pruned orphaned pending snapshot for unjoined player {}", uuid);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Handles player quit: performs mandatory final commit and releases ownership in database.
     */
    public void handlePlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        try {
            ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
            if (session != null) {
                pendingSnapshots.computeIfPresent(uuid, (k, p) -> (p.identity() == null || p.identity().sessionId().equals(session.getSessionId())) ? null : p);
            } else {
                pendingSnapshots.remove(uuid);
            }
            transferRetryCounts.remove(uuid);
            if (session == null) {
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                return;
            }

            if (session.hasFailedLoad() || session.getState() == SessionState.ACQUIRING || session.getState() == SessionState.LOADING) {
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                SessionIdentity identity = session.toIdentity();
                scheduler.runAsync(() -> ownershipService.releaseOwnership(identity, "OFFLINE"));
                return;
            }

            // If session is already released, ownership was already released cleanly (e.g. by handoff)
            if (session.getState() == SessionState.RELEASED) {
                sessionService.removeSession(uuid);
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                handoffsInProgress.remove(uuid);
                return;
            }

            // The outgoing pipeline owns its captured final save, even if its caller timed out.
            if (outgoingTransfers != null && outgoingTransfers.getInFlightTransfer(uuid) != null
                && session.getState() != SessionState.ACTIVE) {
                return;
            }

            // If session is in transfer pending, release ownership if not already released
            if (session.getState() == SessionState.TRANSFER_PENDING) {
                if (session.getFencingToken() > 0) {
                    SessionIdentity identity = session.toIdentity();
                    scheduler.runAsync(() -> ownershipService.releaseOwnership(identity, "OFFLINE"));
                }
                sessionService.removeSession(uuid);
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                handoffsInProgress.remove(uuid);
                return;
            }

            // If transfer handoff is actively executing or player is frozen for transfer, let handoff complete
            // If quit save is already actively executing for this player, avoid duplicate commit
            if (activeQuitSaves.containsKey(uuid)) {
                LOGGER.debug("[QUIT] Player {} quit while quit save is already in progress; avoiding duplicate commit.",
                    player.getName());
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                return;
            }

            // A failed handoff already retained its immutable capture. Do not replace that recovery record.
            if (session.getState() == SessionState.FAILED
                && (stateService.hasPendingFinalSave(uuid) || stateService.hasFinalSaveConflict(uuid))) {
                sessionService.removeSession(session.toIdentity());
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) dirtyTrackingService.clear(uuid);
                return;
            }

            // If transfer handoff is actively executing or player is frozen for transfer, let handoff complete
            if (handoffsInProgress.contains(uuid) || session.getState() == SessionState.FREEZING) {
                LOGGER.debug("[QUIT] Player {} quit during transfer handoff; handoff pipeline is persisting state and releasing ownership.",
                    player.getName());
                joinLockService.remove(uuid);
                if (dirtyTrackingService != null) {
                    dirtyTrackingService.clear(uuid);
                }
                return;
            }

            session.transitionTo(SessionState.SAVING);

            resolveCursorAndClose(player);

            // Capture snapshot synchronously while on player thread to avoid disconnect race
            PlayerStateSnapshot snapshot = null;
            try {
                long currentRevision = session.getRevision();
                long fencingToken = session.getFencingToken();
                snapshot = syncModuleManager.captureSnapshot(player, currentRevision, fencingToken, SyncReason.QUIT, session);
            } catch (Exception e) {
                LOGGER.error("[QUIT] Error capturing player snapshot for {} on quit: {}", player.getName(), e.getMessage(), e);
            }

            final PlayerStateSnapshot finalSnapshot = snapshot;
            CompletableFuture<CommitResult> candidateFuture = (finalSnapshot != null)
                ? stateService.commitDirect(finalSnapshot, session.toIdentity())
                : stateService.saveState(uuid, SyncReason.QUIT, true);
            if (candidateFuture == null) {
                candidateFuture = CompletableFuture.completedFuture(new CommitResult.Failed(
                    new IllegalStateException("Save future was null")
                ));
            }
            final CompletableFuture<CommitResult> saveFuture = candidateFuture;

            SessionIdentity identity = session.toIdentity();
            java.util.function.BiFunction<CommitResult, Throwable, CommitResult> quitHandler = (result, throwable) -> {
                try {
                    if (result instanceof CommitResult.Success success) {
                        LOGGER.debug("[QUIT] Saved player {} at revision {}", player.getName(), success.newRevision());
                        ownershipService.releaseOwnership(identity, "OFFLINE");
                    } else {
                        if (throwable != null) {
                            LOGGER.error("[QUIT] Final save failed for {}; retaining ownership for final-save recovery",
                                player.getName(), throwable);
                        } else if (result instanceof CommitResult.Conflict conflict) {
                            LOGGER.warn("[QUIT] Final save for {} rejected due to conflict (fenced off or superseding writer): {}",
                                player.getName(), conflict.reason());
                        } else {
                            LOGGER.error("[QUIT] Final save failed for {}; retaining ownership for final-save recovery: {}",
                                player.getName(), result);
                        }
                        if (stateService.hasPendingFinalSave(uuid) || result instanceof CommitResult.Conflict) {
                            sessionService.removeSession(identity);
                        } else {
                            // No durable recovery record exists. Keep this failure visible and block stop.
                            session.forceState(SessionState.FAILED);
                        }
                    }
                } finally {
                    joinLockService.remove(uuid);
                    if (messagingService != null) {
                        messagingService.clearTransferPayload(uuid);
                    }
                    if (dirtyTrackingService != null) {
                        dirtyTrackingService.clear(uuid);
                    }
                }
                return result;
            };
            CompletableFuture<CommitResult> completion = (workerExecutor != null)
                ? saveFuture.handleAsync(quitHandler, workerExecutor)
                : saveFuture.handle(quitHandler);
            activeQuitSaves.put(uuid, completion);
            shutdownSaves.add(completion);
            completion.whenComplete((result, error) -> {
                activeQuitSaves.remove(uuid, completion);
                shutdownSaves.remove(completion);
            });
        } finally {
            sessionService.markPlayerOffline(uuid);
        }

    }

    /**
     * Handles an incoming transfer request published over Redis from a destination server.
     * Flushes the player's snapshot to SQL and releases ownership while the player is still online,
     * resolving Velocity/proxy server-switch overlap without kicks.
     */
    public void handleIncomingTransferRequest(dev.lyy.friendlysync.messaging.message.TransferRequestMessage message) {
        if (message == null || message.playerUuid() == null) return;
        if (message.protocolVersion() != dev.lyy.friendlysync.messaging.message.TransferRequestMessage.CURRENT_PROTOCOL_VERSION
            || !"TRANSFER_REQUEST".equals(message.type())
            || message.operationId() == null
            || message.destinationServer() == null
            || message.destinationServer().isBlank()
            || Math.abs(System.currentTimeMillis() - message.timestamp()) > 30_000L) {
            LOGGER.warn("[HANDOFF] Ignoring invalid or stale transfer request");
            return;
        }
        UUID uuid = message.playerUuid();
        if (ownershipService.getServerId().equalsIgnoreCase(message.destinationServer())) {
            return;
        }

        // Guard against uninvolved servers:
        // If the message designates a specific source server and it is not this server,
        // this server is uninvolved. Discard immediately to prevent DB lock contention across the cluster.
        if (message.sourceServer() != null && !message.sourceServer().isBlank()
            && !ownershipService.getServerId().equalsIgnoreCase(message.sourceServer())) {
            return;
        }

        Player resolvedPlayer = null;
        try {
            if (Bukkit.getServer() != null) {
                resolvedPlayer = Bukkit.getPlayer(uuid);
            }
        } catch (Throwable ignored) {
        }
        final Player player = resolvedPlayer;
        boolean isLocalOnline = player != null && player.isOnline();
        boolean hasLocalSession = sessionService.getLocalSession(uuid).isPresent();
        boolean isDesignatedSource = message.sourceServer() != null
            && !message.sourceServer().isBlank()
            && ownershipService.getServerId().equalsIgnoreCase(message.sourceServer());

        // If the player is not online locally on this server and this server is not the designated source
        // (and has no local session in memory), immediately discard to avoid cluster-wide DB lock contention.
        if (!isLocalOnline && !isDesignatedSource && !hasLocalSession) {
            return;
        }

        if (!isLocalOnline) {
            scheduler.runAsync(() -> handleOfflineTransferRequest(message));
            return;
        }


        ActivePlayerSession session = sessionService.getLocalSession(uuid).orElse(null);
        if (session == null || session.getState() == SessionState.RELEASED || session.getState() == SessionState.OFFLINE) {
            return;
        }

        LOGGER.debug("[HANDOFF] Received transfer request for {} from destination server {}. Flushing snapshot and releasing ownership.",
            player.getName(), message.destinationServer());

        scheduler.runForPlayer(player, () -> {
            // Until this entity task runs, quit must still capture the final state.
            if (!player.isOnline() || sessionService.getLocalSession(uuid).orElse(null) != session
                || activeQuitSaves.containsKey(uuid) || session.getState() != SessionState.ACTIVE
                || !handoffsInProgress.add(uuid)) {
                return;
            }
            try {
                if (freezeService != null && !freezeService.freeze(player)) {
                    handoffsInProgress.remove(uuid);
                    return;
                }
                try {
                    player.closeInventory();
                } catch (Exception ignored) {}

                long currentRevision = session.getRevision();
                long fencingToken = session.getFencingToken();
                PlayerStateSnapshot snapshot = syncModuleManager.captureSnapshot(player, currentRevision, fencingToken, SyncReason.TRANSFER, session);

                SessionIdentity identity = session.toIdentity();
                CompletableFuture<CommitResult> handoff = stateService.commitDirect(snapshot, identity).whenCompleteAsync((result, ex) -> {
                    if (ex != null || !(result instanceof CommitResult.Success success)) {
                        if (ex != null) {
                            LOGGER.error("[HANDOFF] Refusing to release {} because final commit failed: {}",
                                player.getName(), ex.getMessage(), ex);
                        } else {
                            LOGGER.error("[HANDOFF] Refusing to release {} because final commit failed: {}",
                                player.getName(), result);
                        }
                        session.forceState(SessionState.FAILED);
                        scheduler.runForPlayer(player, () -> player.kick(Component.text(
                            "Your player data could not be handed off safely. Please reconnect in a moment.", NamedTextColor.RED)));
                        if (!sessionService.isPlayerOnline(uuid)) {
                            sessionService.removeSession(identity);
                            joinLockService.remove(uuid);
                            if (dirtyTrackingService != null) {
                                dirtyTrackingService.clear(uuid);
                            }
                        }
                        handoffsInProgress.remove(uuid);
                        return;
                    }

                    session.setRevision(success.newRevision());
                    if (!ownershipService.releaseOwnership(identity, "RELEASED")) {
                        LOGGER.error("[HANDOFF] Saved {}, but ownership release was rejected", player.getName());
                        session.forceState(SessionState.FAILED);
                        scheduler.runForPlayer(player, () -> player.kick(Component.text(
                            "Your player session could not be released safely. Please reconnect in a moment.", NamedTextColor.RED)));
                        if (!sessionService.isPlayerOnline(uuid)) {
                            sessionService.removeSession(identity);
                            joinLockService.remove(uuid);
                            if (dirtyTrackingService != null) {
                                dirtyTrackingService.clear(uuid);
                            }
                        }
                        handoffsInProgress.remove(uuid);
                        return;
                    }

                    session.forceState(SessionState.RELEASED);
                    stateService.stashTransferPayload(uuid, snapshot, success.newRevision());
                    LOGGER.debug("[HANDOFF] Successfully flushed player {} at revision {} for {}",
                        player.getName(), success.newRevision(), message.destinationServer());
                    if (messagingService != null) {
                        try {
                            messagingService.publishTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(
                                uuid,
                                success.newRevision(),
                                session.getFencingToken(),
                                ownershipService.getServerId(),
                                message.destinationServer()
                            ));
                        } catch (RuntimeException e) {
                            LOGGER.warn("[HANDOFF] Redis notification failed for {}; SQL release is confirmed", uuid, e);
                        }
                    }
                    if (!sessionService.isPlayerOnline(uuid)) {
                        sessionService.removeSession(identity);
                        joinLockService.remove(uuid);
                        if (dirtyTrackingService != null) {
                            dirtyTrackingService.clear(uuid);
                        }
                    } else {
                        // Release cleanup removes the ordinary join lock. Keep an explicit lock
                        // until quit because ownership has already moved away from this server.
                        joinLockService.setExplicitlyLocked(uuid, true);
                        scheduler.runForPlayerLater(player, ownershipService.getAcquisitionWaitLimit(), () -> {
                            if (player.isOnline()) {
                                player.kick(Component.text(
                                    "The proxy did not complete your server switch. Please reconnect.", NamedTextColor.YELLOW));
                            }
                        });
                    }
                    handoffsInProgress.remove(uuid);
                }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool());
                shutdownSaves.add(handoff);
                handoff.whenComplete((result, error) -> shutdownSaves.remove(handoff));
            } catch (Exception e) {
                LOGGER.error("[HANDOFF] Unexpected error during handoff snapshot for {}: {}", player.getName(), e.getMessage(), e);
                session.forceState(SessionState.FAILED);
                handoffsInProgress.remove(uuid);
            }
        }, () -> {});
    }

    private void handleOfflineTransferRequest(dev.lyy.friendlysync.messaging.message.TransferRequestMessage message) {
        UUID uuid = message.playerUuid();
        if (sessionService.isPlayerOnline(uuid)) {
            return;
        }
        ActivePlayerSession localSession = sessionService.getLocalSession(uuid).orElse(null);
        if (localSession != null) {
            // Case 1: Final quit save is actively in flight! Do NOT release ownership; await completion.
            CompletableFuture<CommitResult> pendingQuitSave = activeQuitSaves.get(uuid);
            if (pendingQuitSave != null || localSession.getState() == SessionState.SAVING) {
                LOGGER.debug("[HANDOFF] Player {} ({}) is currently performing final quit save. Awaiting completion before notifying destination {}...",
                    localSession.getUsername(), uuid, message.destinationServer());
                if (pendingQuitSave != null) {
                    pendingQuitSave.whenCompleteAsync((result, ex) -> {
                        transferRetryCounts.remove(uuid);
                        if (result instanceof CommitResult.Success success) {
                            LOGGER.debug("[HANDOFF] Final quit save completed for {} at rev {}. Notifying destination server {}.",
                                uuid, success.newRevision(), message.destinationServer());
                            if (messagingService != null) {
                                messagingService.publishTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(
                                    uuid,
                                    success.newRevision(),
                                    localSession.getFencingToken(),
                                    ownershipService.getServerId(),
                                    message.destinationServer()
                                ));
                            }
                        }
                    }, scheduler::runAsync);
                } else {
                    int retries = transferRetryCounts.computeIfAbsent(uuid, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
                    if (retries <= 15) {
                        scheduler.runAsyncLater(java.time.Duration.ofMillis(100), () -> handleIncomingTransferRequest(message));
                        return;
                    }
                    transferRetryCounts.remove(uuid);
                    LOGGER.error("[HANDOFF] Offline player {} has pending quit save that never registered; retaining ownership for recovery", uuid);
                }
                return;
            }

            // Case 2: Handoff pipeline is already active
            if (localSession.getState() == SessionState.FREEZING || handoffsInProgress.contains(uuid)) {
                LOGGER.debug("[HANDOFF] Transfer handoff already in progress for {} from {}", uuid, message.destinationServer());
                return;
            }

            if (localSession.getState() == SessionState.RELEASED) {
                transferRetryCounts.remove(uuid);
                if (messagingService != null) {
                    messagingService.publishTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(
                        uuid,
                        localSession.getRevision(),
                        localSession.getFencingToken(),
                        ownershipService.getServerId(),
                        message.destinationServer()
                    ));
                }
                return;
            }

            // Case 4: Pre-login handshake never completed (player never joined or finished login)
            if (localSession.getState() == SessionState.ACQUIRING || localSession.getState() == SessionState.LOADING) {
                transferRetryCounts.remove(uuid);
                LOGGER.debug("[HANDOFF] Releasing unjoined pre-login handshake session for {} ({}) in state {} on request from {}.",
                    localSession.getUsername(), uuid, localSession.getState(), message.destinationServer());
                boolean released = ownershipService.releaseOwnership(localSession.toIdentity(), "OFFLINE");
                if (released && messagingService != null) {
                    messagingService.publishTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(
                        uuid,
                        localSession.getRevision(),
                        localSession.getFencingToken(),
                        ownershipService.getServerId(),
                        message.destinationServer()
                    ));
                }
                return;
            }

            // Case 5: Player was ACTIVE and is now offline. A quit save is in flight or about to start!
            if (localSession.getState() == SessionState.ACTIVE) {
                int retries = transferRetryCounts.computeIfAbsent(uuid, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
                if (retries <= 15) {
                    LOGGER.debug("[HANDOFF] Player {} ({}) disconnected while ACTIVE. Postponing transfer request (attempt {}/15) to await quit save...",
                        localSession.getUsername(), uuid, retries);
                    scheduler.runAsyncLater(java.time.Duration.ofMillis(100), () -> handleIncomingTransferRequest(message));
                    return;
                }
                transferRetryCounts.remove(uuid);
                LOGGER.error("[HANDOFF] Offline player {} has no confirmed final save; retaining ownership for recovery", uuid);
                return;
            }
        } else {
            // Guard: Only designated source server should attempt to release orphaned DB ownership
            boolean isDesignatedSource = message.sourceServer() != null
                && !message.sourceServer().isBlank()
                && ownershipService.getServerId().equalsIgnoreCase(message.sourceServer());
            if (!isDesignatedSource) {
                return;
            }
            if (stateService.hasPendingFinalSave(uuid) || stateService.hasFinalSaveConflict(uuid)) {
                return;
            }
            boolean released = ownershipService.releaseOwnershipIfOwner(uuid, "OFFLINE");
            if (released) {
                LOGGER.debug("[HANDOFF] Released orphaned DB ownership for {} claimed by {} on behalf of {}.",
                    uuid, ownershipService.getServerId(), message.destinationServer());
                if (messagingService != null) {
                    messagingService.publishTransferReady(dev.lyy.friendlysync.messaging.message.TransferReadyMessage.create(
                        uuid,
                        0L,
                        0L,
                        ownershipService.getServerId(),
                        message.destinationServer()
                    ));
                }
            }
        }
    }

    /**
     * Freeze and commit while region ticks are still running. A failed stop stays
     * draining so players cannot continue mutating a partially released session.
     */
    public CompletableFuture<Boolean> prepareShutdown() {
        draining = true;
        pendingTransfers.values().forEach(f -> f.cancel(true));
        pendingTransfers.clear();
        CompletableFuture<Boolean> prepared = new CompletableFuture<>();
        try {
            scheduler.runGlobal(() -> {
                java.util.List<CompletableFuture<?>> saves = new java.util.ArrayList<>(shutdownSaves);
                if (outgoingTransfers != null) saves.addAll(outgoingTransfers.getInFlightTransfers());
                for (ActivePlayerSession session : sessionService.getAllLocalSessions()) {
                    UUID uuid = session.getPlayerUuid();
                    var outgoing = outgoingTransfers != null ? outgoingTransfers.getInFlightTransfer(uuid) : null;
                    if (outgoing != null) {
                        continue;
                    }
                    if (activeQuitSaves.containsKey(uuid) || handoffsInProgress.contains(uuid)) continue;
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        if (session.hasFailedLoad() || session.getState() == SessionState.ACQUIRING
                            || session.getState() == SessionState.LOADING) {
                            saves.add(releaseUnloadedSession(session));
                        }
                        continue;
                    }
                    CompletableFuture<CommitResult> saved = new CompletableFuture<>();
                    saves.add(saved);
                    shutdownSaves.add(saved);
                    saved.whenComplete((result, error) -> {
                        activeQuitSaves.remove(uuid, saved);
                        shutdownSaves.remove(saved);
                    });
                    try {
                        scheduler.runForPlayer(player, () -> {
                            try {
                                if (sessionService.getLocalSession(uuid).orElse(null) != session) {
                                    saved.complete(null);
                                    return;
                                }
                                var startedTransfer = outgoingTransfers != null ? outgoingTransfers.getInFlightTransfer(uuid) : null;
                                if (startedTransfer != null) {
                                    startedTransfer.whenComplete((result, error) -> {
                                        if (error != null) saved.completeExceptionally(error);
                                        else saved.complete(null);
                                    });
                                    return;
                                }
                                // Quit/handoff must await this final save, including its ownership release.
                                CompletableFuture<CommitResult> existing = activeQuitSaves.putIfAbsent(uuid, saved);
                                if (existing != null) {
                                    existing.whenComplete((result, error) -> {
                                        if (error != null) saved.completeExceptionally(error);
                                        else saved.complete(result);
                                    });
                                    return;
                                }
                                if (session.hasFailedLoad() || session.getState() == SessionState.ACQUIRING
                                    || session.getState() == SessionState.LOADING) {
                                    releaseUnloadedSession(session).whenComplete((result, error) -> {
                                        if (error != null) saved.completeExceptionally(error);
                                        else saved.complete(result);
                                    });
                                    return;
                                }
                                // Death-screen players are already immobile; capture their post-death state
                                // without the live-transfer freeze guard or forcing a respawn.
                                if (!player.isDead() && session.getState() == SessionState.ACTIVE && freezeService != null
                                    && !freezeService.freeze(player)) {
                                    throw new IllegalStateException("Could not freeze player for shutdown");
                                }
                                joinLockService.setExplicitlyLocked(uuid, true);
                                session.forceState(SessionState.SAVING);
                                PlayerStateSnapshot snapshot = syncModuleManager.captureSnapshot(player,
                                    session.getRevision(), session.getFencingToken(), SyncReason.SHUTDOWN, session);
                                SessionIdentity identity = session.toIdentity();
                                stateService.commitDirect(snapshot, identity).thenApplyAsync(result -> {
                                    if (!(result instanceof CommitResult.Success)
                                        || !ownershipService.releaseOwnership(identity, "OFFLINE")) {
                                        throw new IllegalStateException("Final shutdown save or ownership release failed: " + result);
                                    }
                                    return result;
                                }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool())
                                    .whenComplete((result, error) -> {
                                        if (error != null) saved.completeExceptionally(error);
                                        else saved.complete(result);
                                    });
                            } catch (Exception e) {
                                saved.completeExceptionally(e);
                            }
                        }, () -> saved.completeExceptionally(new IllegalStateException(
                            "Player retired during shutdown preparation; retry stop after quit save")));
                    } catch (Exception e) {
                        saved.completeExceptionally(e);
                    }
                }
                CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                    // Also verify final-save failures returned as values by existing quit pipelines.
                    boolean pending = sessionService.hasActiveSessions();
                    prepared.complete(error == null && !pending && !stateService.hasUnrecoveredFinalSaves());
                });
            });
        } catch (Exception e) {
            prepared.completeExceptionally(e);
        }
        return prepared.orTimeout(30, TimeUnit.SECONDS).exceptionally(error -> {
            LOGGER.error("Graceful shutdown preparation failed; server remains running and locked", error);
            return false;
        });
    }

    private CompletableFuture<CommitResult> releaseUnloadedSession(ActivePlayerSession session) {
        // These players never entered gameplay. Preserve SQL payload; release only this fencing identity.
        CompletableFuture<CommitResult> release = CompletableFuture.supplyAsync(() -> {
            if (!ownershipService.releaseOwnership(session.toIdentity(), "OFFLINE")) {
                throw new IllegalStateException("Unloaded session ownership release failed: " + session.getPlayerUuid());
            }
            return new CommitResult.Success(session.getRevision(), 0);
        }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool());
        shutdownSaves.add(release);
        release.whenComplete((result, error) -> shutdownSaves.remove(release));
        return release;
    }

    /**
     * Shuts down all active local sessions safely by flushing snapshots to database.
     */
    public void handleServerShutdown() {
        draining = true;
        LOGGER.debug("Flushing active player sessions during server shutdown...");
        stateService.beginShutdown();
        java.util.List<CompletableFuture<?>> flushes = new java.util.ArrayList<>(shutdownSaves);
        flushes.addAll(activeQuitSaves.values());
        for (ActivePlayerSession session : sessionService.getAllLocalSessions()) {
            UUID uuid = session.getPlayerUuid();
            if (activeQuitSaves.containsKey(uuid)) {
                continue;
            }
            if (session.hasFailedLoad() || session.getState() == SessionState.ACQUIRING
                || session.getState() == SessionState.LOADING) {
                flushes.add(releaseUnloadedSession(session));
                continue;
            }
            if (session.getState() != SessionState.ACTIVE && session.getState() != SessionState.SAVING) {
                continue;
            }
            SessionIdentity identity = session.toIdentity();
            try {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    continue;
                }
                if (scheduler.isFolia() && !Bukkit.isOwnedByCurrentRegion(player)) {
                    CompletableFuture<Void> foliaFlush = new CompletableFuture<>();
                    flushes.add(foliaFlush);
                    try {
                        scheduler.runForPlayer(player, () -> {
                            try {
                                if (player.isOnline()) {
                                    resolveCursorAndClose(player);
                                    joinLockService.setExplicitlyLocked(uuid, true);
                                    PlayerStateSnapshot snapshot = syncModuleManager.captureSnapshot(
                                        player, session.getRevision(), session.getFencingToken(), SyncReason.SHUTDOWN, session);
                                    stateService.commitDirect(snapshot, identity).whenCompleteAsync((result, error) -> {
                                        if (error == null && result instanceof CommitResult.Success) {
                                            ownershipService.releaseOwnership(identity, "OFFLINE");
                                        } else {
                                            LOGGER.error("Folia shutdown commit failed for {}; retaining SQL lease", uuid, error);
                                            sessionService.removeSession(identity);
                                        }
                                        foliaFlush.complete(null);
                                    }, workerExecutor != null ? workerExecutor : java.util.concurrent.ForkJoinPool.commonPool());
                                } else {
                                    foliaFlush.complete(null);
                                }
                            } catch (Throwable t) {
                                LOGGER.error("Error during Folia shutdown capture for {}: {}", uuid, t.getMessage(), t);
                                foliaFlush.complete(null);
                            }
                        }, () -> {
                            LOGGER.warn("Player {} retired during Folia shutdown dispatch", uuid);
                            foliaFlush.complete(null);
                        });
                    } catch (Throwable t) {
                        LOGGER.warn("Failed to dispatch Folia shutdown capture for {}: {}", uuid, t.getMessage());
                        foliaFlush.complete(null);
                    }
                    continue;
                }
                resolveCursorAndClose(player);
                joinLockService.setExplicitlyLocked(uuid, true);
                PlayerStateSnapshot snapshot = syncModuleManager.captureSnapshot(
                    player, session.getRevision(), session.getFencingToken(), SyncReason.SHUTDOWN, session);
                java.util.function.BiFunction<CommitResult, Throwable, Void> shutdownHandler = (result, error) -> {
                    if (error == null && result instanceof CommitResult.Success) {
                        ownershipService.releaseOwnership(identity, "OFFLINE");
                    } else {
                        LOGGER.error("Shutdown commit failed for {}; retaining SQL lease", uuid, error);
                        sessionService.removeSession(identity);
                    }
                    return null;
                };
                CompletableFuture<?> flush = (workerExecutor != null)
                    ? stateService.commitDirect(snapshot, identity).handleAsync(shutdownHandler, workerExecutor)
                    : stateService.commitDirect(snapshot, identity).handleAsync(shutdownHandler);
                flushes.add(flush);
            } catch (Exception e) {
                LOGGER.error("Failed to capture {} during shutdown; retaining SQL lease", uuid, e);
            }
        }
        try {
            CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("Could not flush {} player session(s) during shutdown; unresolved gameplay ownership remains blocked pending final-save recovery",
                flushes.size(), e);
        }
    }

    private void resolveCursorAndClose(Player player) {
        try {
            if (player.isOnline()) {
                ItemStack cursor = player.getItemOnCursor();
                if (hasItem(cursor)) {
                    java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cursor);
                    if (!leftover.isEmpty()) {
                        for (ItemStack rem : leftover.values()) {
                            if (player.getEnderChest() != null) {
                                java.util.HashMap<Integer, ItemStack> ecLeftover = player.getEnderChest().addItem(rem);
                                for (ItemStack drop : ecLeftover.values()) {
                                    if (hasItem(drop) && player.getWorld() != null && player.getLocation() != null) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                                    }
                                }
                            } else if (hasItem(rem) && player.getWorld() != null && player.getLocation() != null) {
                                player.getWorld().dropItemNaturally(player.getLocation(), rem);
                            }
                        }
                    }
                    player.setItemOnCursor(null);
                }
                player.closeInventory();
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to safely resolve cursor item for {}", player.getName(), t);
        }
    }

    private static boolean isAir(org.bukkit.Material mat) {
        if (mat == null) return false;
        try {
            return mat.isAir();
        } catch (Throwable ignored) {
            try {
                return mat.name().contains("AIR");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static boolean hasItem(ItemStack item) {
        if (item == null) return false;
        try {
            if (item.isEmpty()) return false;
        } catch (Throwable ignored) {}
        try {
            org.bukkit.Material type = item.getType();
            if (type != null && isAir(type)) return false;
        } catch (Throwable ignored) {}
        return true;
    }
}
