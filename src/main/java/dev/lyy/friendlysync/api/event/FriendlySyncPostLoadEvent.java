package dev.lyy.friendlysync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dispatched on player's scheduler thread immediately after a snapshot has been applied.
 */
public final class FriendlySyncPostLoadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long revision;

    public FriendlySyncPostLoadEvent(Player player, long revision) {
        super(false);
        this.player = player;
        this.revision = revision;
    }

    public Player getPlayer() {
        return player;
    }

    public long getRevision() {
        return revision;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
