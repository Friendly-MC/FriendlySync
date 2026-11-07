package dev.lyy.friendlysync.model;

import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.util.Preconditions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe mutable runtime representation of an active player session held by this local server.
 */
public final class ActivePlayerSession {

    private volatile boolean failedLoad;

    public void markLoadFailed() { failedLoad = true; }
    public boolean hasFailedLoad() { return failedLoad; }

    private final UUID playerUuid;
    private final UUID sessionId;
    private final String serverId;
    private final long fencingToken;
    private final String username;

    private final AtomicLong revision;
    private final AtomicReference<SessionState> state;
    private final AtomicLong dirtyGeneration;
    private final AtomicLong lastCommittedGeneration;
    private final AtomicReference<Instant> lastHeartbeat;
    private final Instant createdAt;
    private final AtomicReference<Instant> stateEnteredAt;
    private final AtomicReference<CachedStatistics> cachedStatistics;
    private final AtomicReference<EnderChestSnapshot> cachedEnderChest;
    private final AtomicReference<InventorySnapshot> cachedInventory;
    private final AtomicReference<PlayerStateSnapshot> lastCommittedSnapshot;
    private final java.util.concurrent.atomic.AtomicBoolean enderChestDirty;
    private final java.util.concurrent.atomic.AtomicBoolean inventoryDirty;

    public record CachedStatistics(StatisticsSnapshot snapshot, Instant capturedAt) {
        public CachedStatistics {
            Preconditions.checkNotNull(snapshot, "statistics snapshot cannot be null");
            Preconditions.checkNotNull(capturedAt, "statistics capture time cannot be null");
        }
    }

    public ActivePlayerSession(
        UUID playerUuid,
        UUID sessionId,
        String serverId,
        long fencingToken,
        long initialRevision,
        String username,
        SessionState initialState
    ) {
        this(playerUuid, sessionId, serverId, fencingToken, initialRevision, username, initialState, Instant.now());
    }

    public ActivePlayerSession(
        UUID playerUuid,
        UUID sessionId,
        String serverId,
        long fencingToken,
        long initialRevision,
        String username,
        SessionState initialState,
        Instant createdAt
    ) {
        this.playerUuid = Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        this.sessionId = Preconditions.checkNotNull(sessionId, "sessionId cannot be null");
        this.serverId = Preconditions.checkNotEmpty(serverId, "serverId cannot be empty");
        this.fencingToken = fencingToken;
        this.username = username != null ? username : playerUuid.toString();

        this.revision = new AtomicLong(initialRevision);
        this.state = new AtomicReference<>(Preconditions.checkNotNull(initialState, "initialState cannot be null"));
        this.dirtyGeneration = new AtomicLong(0);
        this.lastCommittedGeneration = new AtomicLong(0);
        this.lastHeartbeat = new AtomicReference<>(Instant.now());
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.stateEnteredAt = new AtomicReference<>(this.createdAt);
        this.cachedStatistics = new AtomicReference<>();
        this.cachedEnderChest = new AtomicReference<>();
        this.cachedInventory = new AtomicReference<>();
        this.lastCommittedSnapshot = new AtomicReference<>();
        this.enderChestDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
        this.inventoryDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
    }


    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getServerId() {
        return serverId;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public String getUsername() {
        return username;
    }

    public SessionIdentity toIdentity() {
        return new SessionIdentity(playerUuid, sessionId, serverId, fencingToken);
    }

    public long getRevision() {
        return revision.get();
    }

    public void setRevision(long newRevision) {
        this.revision.set(newRevision);
    }

    public long incrementRevision() {
        return this.revision.incrementAndGet();
    }

    public SessionState getState() {
        return state.get();
    }

    /**
     * Atomically transitions the session state if the transition is valid according to the state machine.
     *
     * @param target target state
     * @return true if transitioned successfully, false if transition is invalid
     */
    public boolean transitionTo(SessionState target) {
        while (true) {
            SessionState current = state.get();
            if (current == target) {
                return false;
            }
            if (!current.canTransitionTo(target)) {
                return false;
            }
            if (state.compareAndSet(current, target)) {
                stateEnteredAt.set(Instant.now());
                return true;
            }
        }
    }

    public void forceState(SessionState target) {
        state.set(target);
        stateEnteredAt.set(Instant.now());
    }

    public Instant getStateEnteredAt() {
        return stateEnteredAt.get();
    }

    public long markDirty() {
        return dirtyGeneration.incrementAndGet();
    }

    public long getDirtyGeneration() {
        return dirtyGeneration.get();
    }

    public long getLastCommittedGeneration() {
        return lastCommittedGeneration.get();
    }

    public void recordCommittedGeneration(long generation) {
        lastCommittedGeneration.updateAndGet(current -> Math.max(current, generation));
    }

    public boolean isDirty() {
        return dirtyGeneration.get() > lastCommittedGeneration.get();
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat.get();
    }

    public void updateHeartbeat() {
        lastHeartbeat.set(Instant.now());
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Stores statistics and their capture time as one atomic value so checkpoint
     * saves can safely reuse the last complete snapshot.
     */
    public void cacheStatistics(StatisticsSnapshot snapshot, Instant capturedAt) {
        cachedStatistics.set(new CachedStatistics(
            snapshot,
            capturedAt != null ? capturedAt : Instant.now()
        ));
    }

    public Optional<CachedStatistics> getCachedStatistics() {
        return Optional.ofNullable(cachedStatistics.get());
    }

    public void cacheEnderChest(EnderChestSnapshot snapshot) {
        cachedEnderChest.set(snapshot);
        enderChestDirty.set(false);
    }

    public Optional<EnderChestSnapshot> getCachedEnderChest() {
        return Optional.ofNullable(cachedEnderChest.get());
    }

    public void markEnderChestDirty() {
        enderChestDirty.set(true);
    }

    public boolean isEnderChestDirty() {
        return enderChestDirty.get();
    }

    public void clearEnderChestDirty() {
        enderChestDirty.set(false);
    }

    public void cacheInventory(InventorySnapshot snapshot) {
        cachedInventory.set(snapshot);
        inventoryDirty.set(false);
    }

    public Optional<InventorySnapshot> getCachedInventory() {
        return Optional.ofNullable(cachedInventory.get());
    }

    public void markInventoryDirty() {
        inventoryDirty.set(true);
    }

    public boolean isInventoryDirty() {
        return inventoryDirty.get();
    }

    public void clearInventoryDirty() {
        inventoryDirty.set(false);
    }

    public PlayerStateSnapshot getLastCommittedSnapshot() {
        return lastCommittedSnapshot.get();
    }

    public void setLastCommittedSnapshot(PlayerStateSnapshot snapshot) {
        lastCommittedSnapshot.set(snapshot);
    }

    @Override
    public String toString() {
        return "ActivePlayerSession{" +
            "uuid=" + playerUuid +
            ", session=" + sessionId +
            ", token=" + fencingToken +
            ", rev=" + revision.get() +
            ", state=" + state.get() +
            ", dirty=" + isDirty() +
            '}';
    }
}
