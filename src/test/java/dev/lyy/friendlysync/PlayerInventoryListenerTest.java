package dev.lyy.friendlysync;

import dev.lyy.friendlysync.listener.PlayerInventoryListener;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlayerInventoryListenerTest {

    private JoinLockService joinLockService;
    private DirtyTrackingService dirtyTrackingService;
    private PlayerInventoryListener listener;
    private Player player;
    private UUID uuid;

    @BeforeEach
    void setUp() {
        joinLockService = mock(JoinLockService.class);
        dirtyTrackingService = mock(DirtyTrackingService.class);
        listener = new PlayerInventoryListener(joinLockService, dirtyTrackingService);

        uuid = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
    }

    @Test
    @DisplayName("Gameplay actions do not trigger markDirty when player is active")
    void testGameplayActionsDoNotTriggerMarkDirty() {
        when(joinLockService.isLocked(uuid)).thenReturn(false);

        // InventoryClickEvent
        InventoryClickEvent clickEvent = mock(InventoryClickEvent.class);
        when(clickEvent.getWhoClicked()).thenReturn(player);
        listener.onInventoryClick(clickEvent);
        verify(clickEvent, never()).setCancelled(true);

        // InventoryDragEvent
        InventoryDragEvent dragEvent = mock(InventoryDragEvent.class);
        when(dragEvent.getWhoClicked()).thenReturn(player);
        listener.onInventoryDrag(dragEvent);
        verify(dragEvent, never()).setCancelled(true);

        // PlayerDropItemEvent
        PlayerDropItemEvent dropEvent = mock(PlayerDropItemEvent.class);
        when(dropEvent.getPlayer()).thenReturn(player);
        listener.onDropItem(dropEvent);
        verify(dropEvent, never()).setCancelled(true);

        // EntityPickupItemEvent
        EntityPickupItemEvent pickupEvent = mock(EntityPickupItemEvent.class);
        when(pickupEvent.getEntity()).thenReturn(player);
        listener.onPickupItem(pickupEvent);
        verify(pickupEvent, never()).setCancelled(true);

        // BlockPlaceEvent
        BlockPlaceEvent placeEvent = mock(BlockPlaceEvent.class);
        when(placeEvent.getPlayer()).thenReturn(player);
        listener.onBlockPlace(placeEvent);
        verify(placeEvent, never()).setCancelled(true);

        // BlockBreakEvent
        BlockBreakEvent breakEvent = mock(BlockBreakEvent.class);
        when(breakEvent.getPlayer()).thenReturn(player);
        listener.onBlockBreak(breakEvent);
        verify(breakEvent, never()).setCancelled(true);

        // Crucial verification: zero markDirty invocations on continuous gameplay mutations
        verifyNoInteractions(dirtyTrackingService);
    }

    @Test
    @DisplayName("Join lock protection cancels gameplay interactions when player is locked")
    void testJoinLockCancelsInteractions() {
        when(joinLockService.isLocked(uuid)).thenReturn(true);

        InventoryClickEvent clickEvent = mock(InventoryClickEvent.class);
        when(clickEvent.getWhoClicked()).thenReturn(player);
        listener.onInventoryClick(clickEvent);
        verify(clickEvent).setCancelled(true);

        InventoryDragEvent dragEvent = mock(InventoryDragEvent.class);
        when(dragEvent.getWhoClicked()).thenReturn(player);
        listener.onInventoryDrag(dragEvent);
        verify(dragEvent).setCancelled(true);

        PlayerDropItemEvent dropEvent = mock(PlayerDropItemEvent.class);
        when(dropEvent.getPlayer()).thenReturn(player);
        listener.onDropItem(dropEvent);
        verify(dropEvent).setCancelled(true);

        EntityPickupItemEvent pickupEvent = mock(EntityPickupItemEvent.class);
        when(pickupEvent.getEntity()).thenReturn(player);
        listener.onPickupItem(pickupEvent);
        verify(pickupEvent).setCancelled(true);

        BlockPlaceEvent placeEvent = mock(BlockPlaceEvent.class);
        when(placeEvent.getPlayer()).thenReturn(player);
        listener.onBlockPlace(placeEvent);
        verify(placeEvent).setCancelled(true);

        BlockBreakEvent breakEvent = mock(BlockBreakEvent.class);
        when(breakEvent.getPlayer()).thenReturn(player);
        listener.onBlockBreak(breakEvent);
        verify(breakEvent).setCancelled(true);

        verifyNoInteractions(dirtyTrackingService);
    }

    @Test
    @DisplayName("GameMode change marks a checkpoint without an immediate save timer")
    void testGameModeChangeMarksCheckpoint() {
        PlayerGameModeChangeEvent event = mock(PlayerGameModeChangeEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onGameModeChange(event);
        verify(dirtyTrackingService).markDirtyForCheckpoint(uuid);
    }

    @Test
    void completedInventoryMutationMarksCheckpointUnlessLocked() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        listener.onInventoryClickComplete(event);
        verify(dirtyTrackingService).markDirtyForCheckpoint(uuid);
        verify(dirtyTrackingService, never()).markDirty(any());
        when(joinLockService.isLocked(uuid)).thenReturn(true);
        listener.onInventoryClickComplete(event);
        verify(dirtyTrackingService, times(1)).markDirtyForCheckpoint(uuid);
    }

    @Test
    @DisplayName("PlayerMoveEvent and high-frequency combat/movement listeners are eliminated")
    void testSpammyEventsNotRegistered() {
        var methods = PlayerInventoryListener.class.getDeclaredMethods();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.player.PlayerMoveEvent.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.player.PlayerItemHeldEvent.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.entity.FoodLevelChangeEvent.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.entity.EntityPotionEffectEvent.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.entity.EntityRegainHealthEvent.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> Arrays.asList(m.getParameterTypes()).contains(org.bukkit.event.player.PlayerItemDamageEvent.class))).isFalse();
    }

    @Test
    @DisplayName("Ender Chest interactions mark ender chest dirty")
    void testEnderChestInteractionsMarkEnderChestDirty() {
        org.bukkit.inventory.Inventory ecInventory = mock(org.bukkit.inventory.Inventory.class);
        when(player.getEnderChest()).thenReturn(ecInventory);

        InventoryClickEvent clickEvent = mock(InventoryClickEvent.class);
        when(clickEvent.getWhoClicked()).thenReturn(player);
        when(clickEvent.getInventory()).thenReturn(ecInventory);
        listener.onInventoryClickComplete(clickEvent);
        verify(dirtyTrackingService).markEnderChestDirty(uuid);

        InventoryDragEvent dragEvent = mock(InventoryDragEvent.class);
        when(dragEvent.getWhoClicked()).thenReturn(player);
        when(dragEvent.getInventory()).thenReturn(ecInventory);
        listener.onInventoryDragComplete(dragEvent);
        verify(dirtyTrackingService, times(2)).markEnderChestDirty(uuid);

        org.bukkit.event.inventory.InventoryCloseEvent closeEvent = mock(org.bukkit.event.inventory.InventoryCloseEvent.class);
        when(closeEvent.getPlayer()).thenReturn(player);
        when(closeEvent.getInventory()).thenReturn(ecInventory);
        listener.onInventoryClose(closeEvent);
        verify(dirtyTrackingService, times(3)).markEnderChestDirty(uuid);
    }
}
