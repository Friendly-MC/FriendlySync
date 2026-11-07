package dev.lyy.friendlysync.api.event;

import dev.lyy.friendlysync.api.SyncReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dispatched asynchronously or on player context after a commit successfully completes.
 */
public final class FriendlySyncPostSaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SyncReason reason;
    private final long newRevision;
    private final long durationMs;

    public FriendlySyncPostSaveEvent(Player player, SyncReason reason, long newRevision, long durationMs) {
        super(true); // Async event
        this.player = player;
        this.reason = reason;
        this.newRevision = newRevision;
        this.durationMs = durationMs;
    }

    public Player getPlayer() {
        return player;
    }

    public SyncReason getReason() {
        return reason;
    }

    public long getNewRevision() {
        return newRevision;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
