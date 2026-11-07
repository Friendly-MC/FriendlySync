package dev.lyy.friendlysync.sync;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import dev.lyy.friendlysync.scheduler.ScheduledTaskHandle;
import java.util.function.Consumer;
import java.time.Duration;

/**
 * Tracks dirty state mutations per player session, provides debounced save coalescing,
 * and tracks generation markers to prevent race conditions when committing.
 */
public final class DirtyTrackingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirtyTrackingService.class);

    private final PlayerSessionService sessionService;
    private final Consumer<UUID> saveTrigger;
    private final PlatformScheduler scheduler;
    private final Duration coalesceDelay;
    private final LongSupplier nanoTime;
    private final Map<UUID, PendingSave> pendingCoalesceTimers = new HashMap<>();
    private final Map<UUID, CheckpointDue> checkpointTimes = new HashMap<>();

    private record CheckpointDue(ActivePlayerSession session, long dueNanos) {}

    /** Cheap gameplay marker: preserves checkpoint coverage without a save timer per action. */
    public long markDirtyForCheckpoint(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        if (session != null && session.getState() == SessionState.ACTIVE) {
            session.markInventoryDirty();
            return session.markDirty();
        }
        return -1;
    }

    public void markInventoryDirty(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        if (session != null && session.getState() == SessionState.ACTIVE) {
            session.markInventoryDirty();
            session.markDirty();
        }
    }

    public void markEnderChestDirty(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        if (session != null && session.getState() == SessionState.ACTIVE) {
            session.markEnderChestDirty();
            session.markDirty();
        }
    }

    /** Called once per second; spread player checkpoints across the configured interval. */
    public void runCheckpointSlice(Duration interval) {
        java.util.List<UUID> due = new java.util.ArrayList<>();
        synchronized (this) {
            long period = interval.toNanos();
            if (period <= 0) throw new IllegalArgumentException("Checkpoint interval must be positive");
            long now = nanoTime.getAsLong();
            checkpointTimes.entrySet().removeIf(entry ->
                sessionService.getLocalSessionRaw(entry.getKey()) != entry.getValue().session());
            for (ActivePlayerSession session : sessionService.getLocalSessions()) {
                if (session.getState() != SessionState.ACTIVE) continue;
                UUID uuid = session.getPlayerUuid();
                CheckpointDue scheduled = checkpointTimes.get(uuid);
                if (scheduled == null) {
                    scheduled = new CheckpointDue(session, now + Math.floorMod(uuid.getLeastSignificantBits(), period));
                    checkpointTimes.put(uuid, scheduled);
                }
                if (now - scheduled.dueNanos() >= 0) {
                    checkpointTimes.put(uuid, new CheckpointDue(session, now + period));
                    if (session.isDirty()) due.add(uuid);
                }
            }
        }
        for (UUID uuid : due) saveTrigger.accept(uuid);
    }

    private static final class PendingSave {
        final ActivePlayerSession session;
        long markedAt;
        ScheduledTaskHandle task;
        PendingSave(ActivePlayerSession session, long markedAt) {
            this.session = session;
            this.markedAt = markedAt;
        }
    }

    public DirtyTrackingService(PlayerSessionService sessionService, Consumer<UUID> saveTrigger) {
        this(sessionService, saveTrigger, null, Duration.ofSeconds(2));
    }

    public DirtyTrackingService(PlayerSessionService sessionService, Consumer<UUID> saveTrigger, PlatformScheduler scheduler) {
        this(sessionService, saveTrigger, scheduler, Duration.ofSeconds(2));
    }

    public DirtyTrackingService(
        PlayerSessionService sessionService,
        Consumer<UUID> saveTrigger,
        PlatformScheduler scheduler,
        Duration coalesceDelay
    ) {
        this(sessionService, saveTrigger, scheduler, coalesceDelay, System::nanoTime);
    }

    public DirtyTrackingService(PlayerSessionService sessionService, Consumer<UUID> saveTrigger,
                                PlatformScheduler scheduler, Duration coalesceDelay, LongSupplier nanoTime) {
        this.nanoTime = Preconditions.checkNotNull(nanoTime, "nanoTime cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.saveTrigger = Preconditions.checkNotNull(saveTrigger, "saveTrigger cannot be null");
        this.scheduler = scheduler;
        this.coalesceDelay = coalesceDelay != null && !coalesceDelay.isNegative() ? coalesceDelay : Duration.ofSeconds(2);
    }

    /**
     * Marks the player's active session as dirty, incrementing the mutation generation.
     *
     * @param uuid player UUID
     * @return current dirty generation
     */
    public synchronized long markDirty(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        if (session == null || session.getState() != SessionState.ACTIVE) {
            return -1;
        }

        session.markInventoryDirty();
        long gen = session.markDirty();
        PendingSave pending = pendingCoalesceTimers.get(uuid);
        if (pending != null && pending.session == session) {
            pending.markedAt = nanoTime.getAsLong();
        } else {
            clear(uuid);
            pending = new PendingSave(session, nanoTime.getAsLong());
            pendingCoalesceTimers.put(uuid, pending);
            schedule(uuid, pending, coalesceDelay);
        }
        return gen;
    }

    /**
     * Checks if a player currently has uncommitted mutations.
     */
    public boolean isDirty(UUID uuid) {
        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        return session != null && session.isDirty();
    }

    /**
     * Scans active local sessions and triggers checkpoints for players that are dirty.
     * The bounded worker pool applies database backpressure without leaving delayed
     * duplicate checkpoint tasks behind.
     */
    public void runCheckpoint() {
        for (ActivePlayerSession session : sessionService.getLocalSessions()) {
            if (session.getState() == SessionState.ACTIVE && session.isDirty()) {
                LOGGER.debug("Checkpointing dirty player {} (gen={}, lastCommitted={})",
                    session.getPlayerUuid(), session.getDirtyGeneration(), session.getLastCommittedGeneration());
                saveTrigger.accept(session.getPlayerUuid());
            }
        }
    }

    /**
     * Called when a coalesced debounce timer fires.
     */
    public void onCoalesceTimerExpired(UUID uuid, long debounceDelayMs) {
        PendingSave pending;
        synchronized (this) { pending = pendingCoalesceTimers.get(uuid); }
        if (pending != null) expire(uuid, pending, Duration.ofMillis(Math.max(0, debounceDelayMs)));
    }

    private void schedule(UUID uuid, PendingSave pending, Duration delay) {
        if (scheduler == null) return;
        try {
            ScheduledTaskHandle task = scheduler.runAsyncLater(delay, () -> expire(uuid, pending, coalesceDelay));
            if (pendingCoalesceTimers.get(uuid) == pending) pending.task = task;
            else if (task != null) task.cancel();
        } catch (RuntimeException error) {
            pendingCoalesceTimers.remove(uuid, pending);
            // Dirty generation remains set, so the periodic checkpoint can retry.
            throw error;
        }
    }

    private void expire(UUID uuid, PendingSave pending, Duration delay) {
        boolean save;
        synchronized (this) {
            if (pendingCoalesceTimers.get(uuid) != pending) return;
            if (sessionService.getLocalSessionRaw(uuid) != pending.session
                || pending.session.getState() != SessionState.ACTIVE) {
                clear(uuid);
                return;
            }
            long remaining = delay.toNanos() - (nanoTime.getAsLong() - pending.markedAt);
            if (remaining > 0) {
                schedule(uuid, pending, Duration.ofNanos(remaining));
                return;
            }
            pendingCoalesceTimers.remove(uuid, pending);
            save = pending.session.isDirty();
        }
        if (save) saveTrigger.accept(uuid);
    }

    /** Cancels the timer; callbacks already running are fenced by their pending-save identity. */
    public synchronized void clear(UUID uuid) {
        if (uuid == null) return;
        checkpointTimes.remove(uuid);
        PendingSave pending = pendingCoalesceTimers.remove(uuid);
        if (pending != null && pending.task != null) pending.task.cancel();
    }
}
