package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.sync.module.EnderChestSyncModule;
import dev.lyy.friendlysync.sync.module.InventorySyncModule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemLoadSafetyTest {
    @Test
    void corruptArmorDoesNotPartiallyReplaceInventory() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        byte[] bad = {1, 2, 3};
        byte[][] armor = new byte[4][];
        armor[3] = bad;
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(bad)).thenThrow(new IllegalArgumentException("bad item"));
            assertThatThrownBy(() -> new InventorySyncModule().apply(player,
                new InventorySnapshot(new byte[36][], armor, null, 0, null)))
                .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(inventory);
        verify(player, never()).setItemOnCursor(any());
    }

    @Test
    void corruptEnderChestDoesNotReplaceItemsWithAir() {
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(inventory);
        byte[] bad = {1, 2, 3};
        byte[][] slots = new byte[27][];
        slots[26] = bad;
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(bad)).thenThrow(new IllegalArgumentException("bad item"));
            assertThatThrownBy(() -> new EnderChestSyncModule().apply(player, new EnderChestSnapshot(slots)))
                .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(inventory);
    }

    @Test
    void preDecodedInventoryAppliesDirectlyWithoutDeserializingOnApply() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        byte[] itemBytes = {1, 2, 3};
        byte[][] storage = new byte[36][];
        storage[0] = itemBytes;
        InventorySnapshot snapshot = new InventorySnapshot(storage, new byte[4][], null, 0, null);

        ItemStack decodedItem = mock(ItemStack.class);
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(itemBytes)).thenReturn(decodedItem);

            InventorySyncModule module = new InventorySyncModule();
            var decoded = module.preDecode(snapshot);
            assertThat(decoded).isNotNull();
            assertThat(decoded.storage()[0]).isSameAs(decodedItem);

            items.reset();
            module.apply(player, snapshot, decoded);

            verify(inventory).setStorageContents(argThat(arr -> arr[0] == decodedItem));
            items.verifyNoInteractions();
        }
    }

    @Test
    void preDecodedEnderChestAppliesDirectlyWithoutDeserializingOnApply() {
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(inventory);

        byte[] itemBytes = {4, 5, 6};
        byte[][] slots = new byte[27][];
        slots[0] = itemBytes;
        EnderChestSnapshot snapshot = new EnderChestSnapshot(slots);

        ItemStack decodedItem = mock(ItemStack.class);
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(itemBytes)).thenReturn(decodedItem);

            EnderChestSyncModule module = new EnderChestSyncModule();
            var decoded = module.preDecode(snapshot);
            assertThat(decoded).isNotNull();
            assertThat(decoded[0]).isSameAs(decodedItem);

            items.reset();
            module.apply(player, snapshot, decoded);

            verify(inventory).setContents(argThat(arr -> arr[0] == decodedItem));
            items.verifyNoInteractions();
        }
    }

    @Test
    void preDecodeInventoryThrowsOnCorruptedBytes() {
        byte[] bad = {1, 2, 3};
        byte[][] storage = new byte[36][];
        storage[0] = bad;
        InventorySnapshot snapshot = new InventorySnapshot(storage, new byte[4][], null, 0, null);

        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(bad)).thenThrow(new IllegalArgumentException("bad item"));
            InventorySyncModule module = new InventorySyncModule();
            assertThatThrownBy(() -> module.preDecode(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot decode saved inventory item");
        }
    }

    @Test
    void preDecodeEnderChestThrowsOnCorruptedBytes() {
        byte[] bad = {1, 2, 3};
        byte[][] slots = new byte[27][];
        slots[5] = bad;
        EnderChestSnapshot snapshot = new EnderChestSnapshot(slots);

        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(bad)).thenThrow(new IllegalArgumentException("bad item"));
            EnderChestSyncModule module = new EnderChestSyncModule();
            assertThatThrownBy(() -> module.preDecode(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot decode saved ender chest item at slot 5");
        }
    }

    @Test
    void applyWithUpdateClientFalseDoesNotInvokeUpdateInventory() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        InventorySnapshot snapshot = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        InventorySyncModule module = new InventorySyncModule();

        module.apply(player, snapshot, null, false);

        verify(player, never()).updateInventory();
    }

    @Test
    void applyWithUpdateClientTrueInvokesUpdateInventory() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        InventorySnapshot snapshot = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        InventorySyncModule module = new InventorySyncModule();

        module.apply(player, snapshot, null, true);

        verify(player, times(1)).updateInventory();
    }

    @Test
    void syncModuleManagerSkipsClientUpdateOnJoinLoadingState() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        FriendlySyncConfig config = mock(FriendlySyncConfig.class, RETURNS_DEEP_STUBS);
        when(config.isModuleEnabled(anyString())).thenReturn(true);

        SyncModuleManager syncModuleManager = new SyncModuleManager(config);
        InventorySnapshot invSnapshot = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        PlayerStateSnapshot snapshot = mock(PlayerStateSnapshot.class);
        when(snapshot.inventory()).thenReturn(invSnapshot);

        ActivePlayerSession session = mock(ActivePlayerSession.class);
        when(session.getState()).thenReturn(dev.lyy.friendlysync.model.SessionState.LOADING);

        syncModuleManager.applySnapshot(player, snapshot, session);

        verify(player, never()).updateInventory();
    }

    @Test
    void syncModuleManagerSendsClientUpdateWhenActive() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        FriendlySyncConfig config = mock(FriendlySyncConfig.class, RETURNS_DEEP_STUBS);
        when(config.isModuleEnabled(anyString())).thenReturn(true);

        SyncModuleManager syncModuleManager = new SyncModuleManager(config);
        InventorySnapshot invSnapshot = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        PlayerStateSnapshot snapshot = mock(PlayerStateSnapshot.class);
        when(snapshot.inventory()).thenReturn(invSnapshot);

        ActivePlayerSession session = mock(ActivePlayerSession.class);
        when(session.getState()).thenReturn(dev.lyy.friendlysync.model.SessionState.ACTIVE);

        syncModuleManager.applySnapshot(player, snapshot, session);

        verify(player, times(1)).updateInventory();
    }
}

