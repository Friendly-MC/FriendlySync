package dev.lyy.friendlysync.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Dispatched when a cross-server transfer has committed and player is connected to proxy.
 */
public final class FriendlySyncTransferCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String targetServer;
    private final long committedRevision;

    public FriendlySyncTransferCompleteEvent(Player player, String targetServer, long committedRevision) {
        super(false);
        this.player = player;
        this.targetServer = targetServer;
        this.committedRevision = committedRevision;
    }

    public Player getPlayer() {
        return player;
    }

    public String getTargetServer() {
        return targetServer;
    }

    public long getCommittedRevision() {
        return committedRevision;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
