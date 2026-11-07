package dev.lyy.friendlysync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dispatched on player thread when a cross-server transfer is initiated.
 */
public final class FriendlySyncTransferStartEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String targetServer;

    public FriendlySyncTransferStartEvent(Player player, String targetServer) {
        super(false);
        this.player = player;
        this.targetServer = targetServer;
    }

    public Player getPlayer() {
        return player;
    }

    public String getTargetServer() {
        return targetServer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
