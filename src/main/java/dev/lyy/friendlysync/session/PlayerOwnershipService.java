package dev.lyy.friendlysync.session;

import dev.lyy.friendlysync.api.event.FriendlySyncSessionConflictEvent;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.SessionRepository;
import dev.lyy.friendlysync.metrics.MetricsService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.OwnershipResult;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Service managing distributed ownership acquisition, lease heartbeats, and safe ownership releases.
 */
public final class PlayerOwnershipService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerOwnershipService.class);

    private final FriendlySyncConfig config;
    private final PlayerStateRepository playerStateRepository;
    private final SessionRepository sessionRepository;
    private final PlayerSessionService sessionService;
    private final MetricsService metricsService;
    private final Predicate<UUID> onlinePlayerCheck;
    private final java.util.concurrent.atomic.AtomicBoolean renewingLeases = new java.util.concurrent.atomic.AtomicBoolean();

    public PlayerOwnershipService(
        FriendlySyncConfig config,
        PlayerStateRepository playerStateRepository,
        SessionRepository sessionRepository,
        PlayerSessionService sessionService
    ) {
        this(config, playerStateRepository, sessionRepository, sessionService, null, null);
    }

    public PlayerOwnershipService(
        FriendlySyncConfig config,
        PlayerStateRepository playerStateRepository,
        SessionRepository sessionRepository,
        PlayerSessionService sessionService,
        MetricsService metricsService
    ) {
        this(config, playerStateRepository, sessionRepository, sessionService, metricsService, null);
    }

    public PlayerOwnershipService(
        FriendlySyncConfig config,
        PlayerStateRepository playerStateRepository,
        SessionRepository sessionRepository,
        PlayerSessionService sessionService,
        MetricsService metricsService,
        Predicate<UUID> onlinePlayerCheck
    ) {
        this.config = Preconditions.checkNotNull(config, "config cannot be null");
        this.playerStateRepository = Preconditions.checkNotNull(playerStateRepository, "playerStateRepository cannot be null");
        this.sessionRepository = Preconditions.checkNotNull(sessionRepository, "sessionRepository cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.metricsService = metricsService;
        this.onlinePlayerCheck = onlinePlayerCheck != null ? onlinePlayerCheck : sessionService::isPlayerOnline;
    }

    @FunctionalInterface
    public interface SessionReleaseListener extends Consumer<UUID> {
        void onSessionReleased(UUID uuid, SessionIdentity session);

        @Override
        default void accept(UUID uuid) {
            onSessionReleased(uuid, null);
        }
    }

    private SessionReleaseListener onSessionReleased;

    public void setOnSessionReleased(Consumer<UUID> onSessionReleased) {
        if (onSessionReleased instanceof SessionReleaseListener srl) {
            this.onSessionReleased = srl;
        } else if (onSessionReleased != null) {
            this.onSessionReleased = (uuid, session) -> onSessionReleased.accept(uuid);
        } else {
            this.onSessionReleased = null;
        }
    }

    /**
     * Attempts atomic ownership acquisition for the given player.
     */
    public OwnershipResult acquireOwnership(UUID uuid, String username) {
        UUID sessionId = UUID.randomUUID();
        OwnershipResult result = playerStateRepository.acquireOwnership(
            uuid,
            username,
            config.getServerId(),
            sessionId,
            config.getSessionLeaseTimeout()
        );

        if (result instanceof OwnershipResult.Acquired acquired) {
            final long fencingToken = acquired.session().fencingToken();
            CompletableFuture.runAsync(() -> {
                try {
                    sessionRepository.recordSessionStart(
                        sessionId,
                        uuid,
                        config.getServerId(),
                        fencingToken,
                        "ACQUIRING"
                    );
                } catch (Exception e) {
                    LOGGER.warn("[OWNERSHIP] Failed to record session start for {}: {}", uuid, e.getMessage());
                }
            });
            sessionService.registerSession(acquired.session(), acquired.currentRevision(), username, SessionState.ACQUIRING);
            LOGGER.debug("[OWNERSHIP] Acquired ownership for player {} (session={}, token={}, rev={})",
                uuid, sessionId, acquired.session().fencingToken(), acquired.currentRevision());
        } else if (result instanceof OwnershipResult.AlreadyOwned owned) {
            LOGGER.debug("[OWNERSHIP] Player {} already owned by server {} (token={}, leaseExpires={}, state={})",
                uuid, owned.ownerServer(), owned.currentFencingToken(), owned.leaseExpiresAt(), owned.sessionState());
        } else if (result instanceof OwnershipResult.Conflict conflict) {
            LOGGER.warn("[OWNERSHIP] Ownership conflict for player {} (reason={})",
                uuid, conflict.reason());
            if (metricsService != null) {
                metricsService.recordConflict();
            }
            try {
                Bukkit.getPluginManager().callEvent(
                    new FriendlySyncSessionConflictEvent(uuid, 0L, conflict.reason())
                );
            } catch (Throwable ignored) {
            }
        }

        return result;
    }

    /**
     * Periodic task to renew leases for all active local player sessions in batched operations
     * and evict orphaned sessions whose pre-login handshake or connection was abandoned.
     */
    public void renewActiveLeases() {
        if (!renewingLeases.compareAndSet(false, true)) return;
        try {
            renewActiveLeasesOnce();
        } finally {
            renewingLeases.set(false);
        }
    }

    private void renewActiveLeasesOnce() {
        List<ActivePlayerSession> candidates = new ArrayList<>();
        List<ActivePlayerSession> orphanedToRelease = new ArrayList<>();
        Instant now = Instant.now();

        for (ActivePlayerSession session : sessionService.getAllLocalSessions()) {
            if (session.getState() == SessionState.OFFLINE
                || session.getState() == SessionState.RELEASED
                || session.getFencingToken() <= 0) {
                continue;
            }

            boolean online = onlinePlayerCheck.test(session.getPlayerUuid());

            if (session.getState() == SessionState.ACTIVE) {
                if (online) {
                    candidates.add(session);
                } else {
                    LOGGER.warn("[LEASE] Player {} is no longer online locally while session is ACTIVE. Retaining ownership pending final-save recovery.",
                        session.getPlayerUuid());
                    // No final snapshot is confirmed. Preserve ownership for recovery.
                }
            } else if (session.getState() == SessionState.ACQUIRING
                || session.getState() == SessionState.LOADING) {
                // Handshake in progress: check if it has timed out (> 30 seconds)
                if (Duration.between(session.getCreatedAt(), now).compareTo(Duration.ofSeconds(30)) > 0) {
                    if (!online) {
                        LOGGER.warn("[LEASE] Handshake for player {} ({}) timed out in state {} after 30s without joining. Releasing orphaned ownership.",
                            session.getUsername(), session.getPlayerUuid(), session.getState());
                        orphanedToRelease.add(session);
                    } else {
                        candidates.add(session);
                    }
                } else {
                    // Fresh pending session (< 30s): initial acquire already granted sessionLeaseTimeout (45s).
                    // Only renew if player is online.
                    if (online) {
                        candidates.add(session);
                    }
                }
            } else if (session.getState() == SessionState.SAVING
                || session.getState() == SessionState.TRANSFER_PENDING
                || session.getState() == SessionState.FREEZING) {
                if (Duration.between(session.getStateEnteredAt(), now).compareTo(Duration.ofSeconds(30)) > 0 && !online) {
                    LOGGER.warn("[LEASE] Player {} ({}) stuck in transient state {} without being online. Retaining ownership pending final-save recovery.",
                        session.getUsername(), session.getPlayerUuid(), session.getState());
                    // An in-flight final save must not lose ownership to a timer.
                    candidates.add(session);
                } else {
                    candidates.add(session);
                }
            } else {
                if (online) {
                    candidates.add(session);
                }
            }
        }

        // Evict and release all orphaned sessions immediately
        for (ActivePlayerSession orphaned : orphanedToRelease) {
            try {
                releaseOwnership(orphaned.toIdentity(), "OFFLINE");
            } catch (Exception e) {
                LOGGER.error("[LEASE] Error releasing orphaned session for {}: {}", orphaned.getPlayerUuid(), e.getMessage());
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        try {
            Set<UUID> renewedUuids = playerStateRepository.renewLeases(candidates, config.getSessionLeaseTimeout());

            List<UUID> renewedSessionIds = new ArrayList<>();
            for (ActivePlayerSession session : candidates) {
                if (renewedUuids.contains(session.getPlayerUuid())) {
                    session.updateHeartbeat();
                    renewedSessionIds.add(session.getSessionId());
                } else if (sessionService.getLocalSession(session.getPlayerUuid()).orElse(null) != session
                    || session.getState() == SessionState.RELEASED
                    || session.getState() == SessionState.OFFLINE) {
                    // Released (transfer, quit) or replaced while this batch was in flight; nothing was fenced.
                    LOGGER.debug("[LEASE] Skipping renewal result for player {}: session ended during renewal",
                        session.getPlayerUuid());
                } else if (stillOwnedBy(session)) {
                    // Dropping the local session here would leave the player online with nothing
                    // saving their progress, and the row owned until an administrator unlocks it.
                    // Every commit is still fenced by session id and token, so keeping it is safe.
                    LOGGER.warn("[LEASE] Lease renewal for player {} was not confirmed, but SQL still lists this session as owner; keeping it and retrying",
                        session.getPlayerUuid());
                } else {
                    LOGGER.error("[LEASE] Failed to renew lease for player {}. Session may have been fenced off by newer writer!",
                        session.getPlayerUuid());
                    sessionService.invalidateLocalSession(session.getPlayerUuid(), session.getFencingToken() + 1);
                    notifySessionFenced(session.getPlayerUuid());
                    if (metricsService != null) {
                        metricsService.recordConflict();
                    }
                    try {
                        Bukkit.getPluginManager().callEvent(
                            new FriendlySyncSessionConflictEvent(
                                session.getPlayerUuid(),
                                session.getFencingToken(),
                                "Lease renewal rejected by newer fencing token"
                            )
                        );
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (!renewedSessionIds.isEmpty()) {
                sessionRepository.recordHeartbeats(renewedSessionIds);
            }
        } catch (SQLException e) {
            LOGGER.warn("[LEASE] Database error during batched lease heartbeat: {}", e.getMessage());
        }
    }

    public String getServerId() {
        return config.getServerId();
    }

    public Duration getAcquisitionWaitLimit() {
        return config.getRecoveryMaximumWait().compareTo(config.getTransferTimeout()) < 0
            ? config.getRecoveryMaximumWait()
            : config.getTransferTimeout();
    }

    /** Read-only handoff probe; never downloads inventory or locks a row for acquisition. */
    public boolean isOwnershipReleased(UUID uuid) throws SQLException {
        return playerStateRepository.findMetadata(uuid).map(state -> state.ownerServer() == null).orElse(true);
    }

    /** 8 missed presence heartbeats (15s) and 2+ minutes past lease expiry: the owner process is gone. */
    static final Duration OWNER_DEAD_AFTER = Duration.ofMinutes(2);

    /** Takes over a lease whose owner server crashed and never came back; see repository for guards. */
    public boolean releaseAbandonedLease(UUID uuid, String owner, Instant observedExpiry) {
        if (owner == null || owner.isBlank() || observedExpiry == null || owner.equalsIgnoreCase(config.getServerId())) {
            return false;
        }
        try {
            boolean released = playerStateRepository.releaseAbandonedLease(uuid, owner, observedExpiry, OWNER_DEAD_AFTER);
            if (released) {
                LOGGER.warn("[OWNERSHIP] Released abandoned lease for {} from offline server {} (expired {}); restored from last saved data",
                    uuid, owner, observedExpiry);
            }
            return released;
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Error releasing abandoned lease for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    public boolean breakExpiredLease(UUID uuid, String expectedOwner, Instant expectedExpiry) {
        if (expectedOwner == null || expectedOwner.isBlank()) {
            // Without an expected owner there is no identity to compare atomically.
            return false;
        }
        try {
            return playerStateRepository.breakExpiredLease(uuid, expectedOwner, expectedExpiry);
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Error breaking expired lease for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    public boolean forceReleaseOwnership(UUID uuid, String finalState) {
        try {
            boolean released = playerStateRepository.forceReleaseOwnership(uuid, finalState);
            if (released) {
                notifySessionReleased(uuid, null);
            }
            return released;
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Error force releasing ownership for {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /**
     * Updates the persistent session state string in the database.
     */
    public boolean updateSessionState(SessionIdentity session, String newState) {
        try {
            return playerStateRepository.updateSessionState(
                session.playerUuid(),
                session.sessionId(),
                session.fencingToken(),
                newState
            );
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Failed to update session state for player {}: {}", session.playerUuid(), e.getMessage());
            return false;
        }
    }

    /**
     * Safely releases ownership of a player session.
     */
    public boolean releaseOwnership(SessionIdentity session, String finalState) {
        try {
            boolean released = playerStateRepository.releaseOwnership(
                session.playerUuid(),
                session.sessionId(),
                session.fencingToken(),
                finalState
            );
            if (!released) {
                LOGGER.warn("[OWNERSHIP] Release rejected for player {} (session={}, token={}); local state retained",
                    session.playerUuid(), session.sessionId(), session.fencingToken());
                return false;
            }
            UUID endedSessionId = session.sessionId();
            CompletableFuture.runAsync(() -> {
                try {
                    sessionRepository.recordSessionEnd(endedSessionId, finalState);
                } catch (Exception e) {
                    LOGGER.warn("[OWNERSHIP] Failed to record session end for {}: {}", endedSessionId, e.getMessage());
                }
            });
            sessionService.removeSession(session);
            notifySessionReleased(session.playerUuid(), session);
            LOGGER.debug("[OWNERSHIP] Released ownership for player {} (state={})", session.playerUuid(), finalState);
            return released;
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Failed to release ownership for player {}: {}", session.playerUuid(), e.getMessage());
            return false;
        }
    }

    /**
     * Safely releases ownership if the record is currently owned by this server,
     * cleaning up any local session state.
     */
    public boolean releaseOwnershipIfOwner(UUID uuid, String finalState) {
        try {
            boolean released = playerStateRepository.releaseOwnershipIfOwner(
                uuid,
                config.getServerId(),
                finalState
            );
            if (released) {
                ActivePlayerSession local = sessionService.getLocalSession(uuid)
                    .filter(s -> s.getServerId().equals(config.getServerId()))
                    .orElse(null);
                SessionIdentity identity = local != null ? local.toIdentity() : null;
                if (local != null) {
                    sessionService.removeSession(identity);
                }
                notifySessionReleased(uuid, identity);
                LOGGER.debug("[OWNERSHIP] Released ownership if owner for player {} (server={}, state={})",
                    uuid, config.getServerId(), finalState);
            }
            return released;
        } catch (SQLException e) {
            LOGGER.error("[OWNERSHIP] Failed to release ownership if owner for player {}: {}", uuid, e.getMessage());
            return false;
        }
    }

    /** True unless SQL confirms another session, token or server now owns the row. */
    private boolean stillOwnedBy(ActivePlayerSession session) {
        try {
            return playerStateRepository.findMetadata(session.getPlayerUuid())
                .map(row -> session.getSessionId().equals(row.activeSessionId())
                    && row.fencingToken() == session.getFencingToken()
                    && session.getServerId().equalsIgnoreCase(row.ownerServer()))
                .orElse(false);
        } catch (SQLException e) {
            // Cannot verify: keep the session. A fenced commit fails safely; a dropped one loses progress.
            LOGGER.warn("[LEASE] Could not verify ownership for {} after a failed renewal: {}", session.getPlayerUuid(), e.getMessage());
            return true;
        }
    }

    private Consumer<UUID> onSessionFenced;

    /** Called after a local session is dropped because SQL shows another owner; the player must not keep playing unsaved. */
    public void setOnSessionFenced(Consumer<UUID> onSessionFenced) {
        this.onSessionFenced = onSessionFenced;
    }

    private void notifySessionFenced(UUID uuid) {
        if (onSessionFenced != null) {
            try {
                onSessionFenced.accept(uuid);
            } catch (Throwable e) {
                LOGGER.warn("[LEASE] Could not disconnect fenced player {}: {}", uuid, e.getMessage());
            }
        }
    }

    private void notifySessionReleased(UUID uuid, SessionIdentity session) {
        if (onSessionReleased != null) {
            try {
                onSessionReleased.onSessionReleased(uuid, session);
            } catch (Throwable ignored) {
            }
        }
    }
}
