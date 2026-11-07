package dev.lyy.friendlysync.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Dispatched when a session concurrency conflict or fencing token rejection occurs.
 */
public final class FriendlySyncSessionConflictEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUuid;
    private final long attemptedFencingToken;
    private final String reason;

    public FriendlySyncSessionConflictEvent(UUID playerUuid, long attemptedFencingToken, String reason) {
        super(true); // Async event
        this.playerUuid = playerUuid;
        this.attemptedFencingToken = attemptedFencingToken;
        this.reason = reason;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public long getAttemptedFencingToken() {
        return attemptedFencingToken;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
