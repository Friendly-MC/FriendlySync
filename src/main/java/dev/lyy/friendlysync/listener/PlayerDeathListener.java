package dev.lyy.friendlysync.listener;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles death edge cases ensuring post-death item drops and respawns update dirty state correctly,
 * and suppresses item/exp drops when a player dies during an active cross-server transfer to prevent duplication.
 */
public final class PlayerDeathListener implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDeathListener.class);

    private final DirtyTrackingService dirtyTrackingService;
    private final PlatformScheduler scheduler;
    private final PlayerSessionService sessionService;

    public PlayerDeathListener(
        DirtyTrackingService dirtyTrackingService,
        PlatformScheduler scheduler,
        PlayerSessionService sessionService
    ) {
        this.dirtyTrackingService = Preconditions.checkNotNull(dirtyTrackingService, "dirtyTrackingService cannot be null");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeathEarly(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ActivePlayerSession session = sessionService.getLocalSession(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }

        // If player is in the middle of a cross-server transfer, their items have already
        // been captured into the transfer snapshot to be loaded on the target server.
        // We MUST suppress world drops and exp drops to prevent duplicating items on this server!
        SessionState state = session.getState();
        if (state == SessionState.FREEZING || state == SessionState.SAVING || state == SessionState.TRANSFER_PENDING) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            LOGGER.warn("[DEATH-TRANSFER] Suppressed death drops for {} because transfer is already in progress ({})",
                player.getName(), state);
        }
    }

    private static final java.util.Set<java.util.UUID> DEATH_KEEP_INVENTORY_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // False is a final event decision too; it must override the world's keep-inventory rule.
    private static final java.util.Map<java.util.UUID, Boolean> DEATH_KEEP_LEVEL_PLAYERS = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean hasKeepInventory(java.util.UUID uuid) {
        return uuid != null && DEATH_KEEP_INVENTORY_PLAYERS.contains(uuid);
    }

    public static boolean hasKeepLevel(java.util.UUID uuid) {
        return hasKeepLevel(uuid, false);
    }

    public static boolean hasKeepLevel(java.util.UUID uuid, boolean fallback) {
        return uuid == null ? fallback : DEATH_KEEP_LEVEL_PLAYERS.getOrDefault(uuid, fallback);
    }

    public static void clear(java.util.UUID uuid) {
        if (uuid != null) {
            DEATH_KEEP_INVENTORY_PLAYERS.remove(uuid);
            DEATH_KEEP_LEVEL_PLAYERS.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.isCancelled()) return;
        Player player = event.getEntity();
        if (player.getUniqueId() != null) {
            if (event.getKeepInventory()) {
                DEATH_KEEP_INVENTORY_PLAYERS.add(player.getUniqueId());
            } else {
                DEATH_KEEP_INVENTORY_PLAYERS.remove(player.getUniqueId());
            }
            DEATH_KEEP_LEVEL_PLAYERS.put(player.getUniqueId(), event.getKeepLevel());
        }

        dirtyTrackingService.markDirty(player.getUniqueId());

        // Also schedule a check on the player's thread next tick after drops spawn
        scheduler.runForPlayer(player, () -> {
            dirtyTrackingService.markDirty(player.getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.getUniqueId() != null) {
            DEATH_KEEP_INVENTORY_PLAYERS.remove(player.getUniqueId());
            DEATH_KEEP_LEVEL_PLAYERS.remove(player.getUniqueId());
        }
        scheduler.runForPlayer(player, () -> {
            dirtyTrackingService.markDirty(player.getUniqueId());
        });
    }
}
