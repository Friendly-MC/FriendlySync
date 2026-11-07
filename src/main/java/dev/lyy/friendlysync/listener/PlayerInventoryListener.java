package dev.lyy.friendlysync.listener;

import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;

/**
 * Enforces interaction locking during synchronization.
 * Gameplay mutations mark checkpoints without starting per-action save timers.
 */
public final class PlayerInventoryListener implements Listener {

    private final JoinLockService joinLockService;
    private final DirtyTrackingService dirtyTrackingService;

    public PlayerInventoryListener(JoinLockService joinLockService, DirtyTrackingService dirtyTrackingService) {
        this.joinLockService = Preconditions.checkNotNull(joinLockService, "joinLockService cannot be null");
        this.dirtyTrackingService = Preconditions.checkNotNull(dirtyTrackingService, "dirtyTrackingService cannot be null");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (joinLockService.isLocked(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (joinLockService.isLocked(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            if (joinLockService.isLocked(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (joinLockService.isLocked(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            if (joinLockService.isLocked(uuid)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (joinLockService.isLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        dirtyTrackingService.markDirtyForCheckpoint(event.getPlayer().getUniqueId());
    }

    private void markCheckpoint(Player player) {
        if (!joinLockService.isLocked(player.getUniqueId())) {
            dirtyTrackingService.markDirtyForCheckpoint(player.getUniqueId());
        }
    }

    private boolean isEnderChest(Player player, org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) return false;
        try {
            if (player != null && inventory.equals(player.getEnderChest())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return inventory.getType() == InventoryType.ENDER_CHEST;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClickComplete(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markCheckpoint(player);
            if (isEnderChest(player, event.getInventory())) {
                dirtyTrackingService.markEnderChestDirty(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDragComplete(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markCheckpoint(player);
            if (isEnderChest(player, event.getInventory())) {
                dirtyTrackingService.markEnderChestDirty(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && isEnderChest(player, event.getInventory())) {
            dirtyTrackingService.markEnderChestDirty(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropComplete(PlayerDropItemEvent event) { markCheckpoint(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupComplete(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) markCheckpoint(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceComplete(BlockPlaceEvent event) { markCheckpoint(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakComplete(BlockBreakEvent event) { markCheckpoint(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) { markCheckpoint(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) { markCheckpoint(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (joinLockService.isLocked(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (joinLockService.isLocked(uuid)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (joinLockService.isLocked(uuid)) {
            event.setCancelled(true);
        }
    }
}
