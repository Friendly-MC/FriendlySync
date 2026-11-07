package dev.lyy.friendlysync.api.event;

import dev.lyy.friendlysync.api.SyncReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dispatched on player's scheduler thread immediately before snapshot capture begins.
 */
public final class FriendlySyncPreSaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final SyncReason reason;
    private final long currentRevision;

    public FriendlySyncPreSaveEvent(Player player, SyncReason reason, long currentRevision) {
        super(false);
        this.player = player;
        this.reason = reason;
        this.currentRevision = currentRevision;
    }

    public Player getPlayer() {
        return player;
    }

    public SyncReason getReason() {
        return reason;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
