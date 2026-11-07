package dev.lyy.friendlysync.lock;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * Handles freezing players before snapshot capture during cross-server transfers,
 * closing open containers, resolving cursor items, and safely unfreezing if a transfer fails.
 */
public final class TransferFreezeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransferFreezeService.class);

    private final PlayerSessionService sessionService;
    private final JoinLockService joinLockService;

    public TransferFreezeService(PlayerSessionService sessionService, JoinLockService joinLockService) {
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.joinLockService = Preconditions.checkNotNull(joinLockService, "joinLockService cannot be null");
    }

    /**
     * Freezes a player on their owning scheduler thread.
     * Closes open container views and resolves cursor items into storage if possible.
     */
    public boolean freeze(Player player) {
        Preconditions.checkNotNull(player, "player cannot be null");

        if (player.isDead() || player.getHealth() <= 0.0) {
            LOGGER.warn("Cannot freeze dead player {} for transfer", player.getName());
            return false;
        }

        ActivePlayerSession session = sessionService.getLocalSession(player.getUniqueId()).orElse(null);
        if (session == null) {
            return false;
        }

        if (!session.transitionTo(SessionState.FREEZING)) {
            LOGGER.warn("Could not transition session {} to FREEZING from {}", player.getUniqueId(), session.getState());
            return false;
        }

        joinLockService.setExplicitlyLocked(player.getUniqueId(), true);

        // Eject vehicles and passengers to prevent Folia/Canvas regional thread split states
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.eject();

        // Safely resolve item on cursor into inventory or enderchest BEFORE closing container views
        ItemStack cursor = player.getItemOnCursor();
        if (hasItem(cursor)) {
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(cursor);
            if (!leftover.isEmpty()) {
                for (ItemStack rem : leftover.values()) {
                    if (player.getEnderChest() != null) {
                        HashMap<Integer, ItemStack> ecLeftover = player.getEnderChest().addItem(rem);
                        for (ItemStack drop : ecLeftover.values()) {
                            if (hasItem(drop) && player.getWorld() != null && player.getLocation() != null) {
                                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                            }
                        }
                    } else if (hasItem(rem) && player.getWorld() != null && player.getLocation() != null) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rem);
                    }
                }
            }
            player.setItemOnCursor(null);
        }

        player.closeInventory();

        LOGGER.debug("Player {} successfully frozen for transfer", player.getName());
        return true;
    }

    /**
     * Safely unfreezes a player when a transfer attempt fails.
     */
    public void unfreeze(Player player) {
        Preconditions.checkNotNull(player, "player cannot be null");

        ActivePlayerSession session = sessionService.getLocalSession(player.getUniqueId()).orElse(null);
        if (session != null) {
            session.transitionTo(SessionState.ACTIVE);
        }

        joinLockService.setExplicitlyLocked(player.getUniqueId(), false);
        LOGGER.info("Player {} unfrozen after failed transfer, resumed ACTIVE state", player.getName());
    }

    private static boolean isAir(org.bukkit.Material mat) {
        if (mat == null) return false;
        try {
            return mat.isAir();
        } catch (Throwable ignored) {
            try {
                return mat.name().contains("AIR");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static boolean hasItem(ItemStack item) {
        if (item == null) return false;
        try {
            if (item.isEmpty()) return false;
        } catch (Throwable ignored) {}
        try {
            org.bukkit.Material type = item.getType();
            if (type != null && isAir(type)) return false;
        } catch (Throwable ignored) {}
        return true;
    }
}
