package dev.lyy.friendlysync;

import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.sync.module.EnderChestSyncModule;
import dev.lyy.friendlysync.sync.module.InventorySyncModule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CursorAndEnderChestItemSafetyTest {

    @Test
    void applyingBothInventoriesPreservesCursorOverflowAndInvalidatesItemCaches() {
        var config = mock(dev.lyy.friendlysync.config.FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
            false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16));
        when(config.isModuleEnabled("inventory")).thenReturn(true);
        when(config.isModuleEnabled("ender-chest")).thenReturn(true);
        var modules = new dev.lyy.friendlysync.sync.SyncModuleManager(config);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory enderChest = mock(Inventory.class);
        ItemStack cursor = mock(ItemStack.class);
        when(cursor.isEmpty()).thenReturn(false);
        when(cursor.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(inventory.addItem(cursor)).thenReturn(new HashMap<>(java.util.Map.of(0, cursor)));
        var rescued = new java.util.concurrent.atomic.AtomicReference<ItemStack>();
        when(enderChest.addItem(cursor)).thenAnswer(call -> { rescued.set(cursor); return new HashMap<>(); });
        doAnswer(call -> { rescued.set(null); return null; }).when(enderChest).setContents(any());
        var invSnapshot = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, new byte[] {1});
        var ecSnapshot = new EnderChestSnapshot(new byte[27][]);
        UUID uuid = UUID.randomUUID();
        var snapshot = new dev.lyy.friendlysync.model.PlayerStateSnapshot(uuid, 4, 1, invSnapshot, ecSnapshot,
            null, null, null, java.util.List.of(), null, null, null, java.util.Map.of(), java.time.Instant.now(), 3);
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 4, "Player", SessionState.LOADING);
        ItemStack[] storage = new ItemStack[36];
        java.util.Arrays.fill(storage, cursor);
        var decoded = new InventorySyncModule.DecodedInventory(storage, new ItemStack[4], null, cursor);

        modules.applySnapshot(player, snapshot, session, decoded, new ItemStack[27]);

        assertThat(rescued.get()).isSameAs(cursor);
        assertThat(session.isInventoryDirty()).isTrue();
        assertThat(session.isEnderChestDirty()).isTrue();
        assertThat(session.isDirty()).isTrue();
        verify(player, never()).updateInventory();
    }

    @Test
    @DisplayName("InventorySyncModule.apply moves cursor item into storage instead of leaving it on cursor to prevent deletion")
    void applyResolvesCursorItemIntoInventoryStorage() {
        InventorySyncModule module = new InventorySyncModule();
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inv);

        ItemStack cursorItem = mock(ItemStack.class);
        when(cursorItem.isEmpty()).thenReturn(false);
        when(cursorItem.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});

        byte[] cursorBytes = new byte[]{1, 2, 3};
        InventorySnapshot snapshot = new InventorySnapshot(
                new byte[36][], new byte[4][], null, 0, cursorBytes
        );

        // When adding to inventory, inventory has room (empty leftover map)
        when(inv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(cursorBytes)).thenReturn(cursorItem);
            module.apply(player, snapshot);
        }

        // addItem must have been called with the cursor item
        verify(inv).addItem(cursorItem);
        // And cursor must be cleared (null) so client doesn't overwrite it with air
        verify(player).setItemOnCursor(null);
    }

    @Test
    @DisplayName("InventorySyncModule.apply drops cursor item safely if inventory is completely full")
    void applyDropsCursorItemIfInventoryFull() {
        InventorySyncModule module = new InventorySyncModule();
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        World world = mock(World.class);
        Location loc = mock(Location.class);

        when(player.getInventory()).thenReturn(inv);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(loc);

        ItemStack leftoverItem = mock(ItemStack.class);
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        leftovers.put(0, leftoverItem);

        // Inventory is full: returns leftover
        when(inv.addItem(any(ItemStack.class))).thenReturn(leftovers);

        byte[] cursorBytes = new byte[]{1, 2, 3};
        InventorySnapshot snapshot = new InventorySnapshot(
                new byte[36][], new byte[4][], null, 0, cursorBytes
        );

        try (var items = mockStatic(ItemStack.class)) {
            ItemStack cursorItem = mock(ItemStack.class);
            when(cursorItem.isEmpty()).thenReturn(false);
            items.when(() -> ItemStack.deserializeBytes(cursorBytes)).thenReturn(cursorItem);
            module.apply(player, snapshot);
        }

        // Item must be safely dropped in the world so it is never destroyed
        verify(world).dropItemNaturally(eq(loc), eq(leftoverItem));
        verify(player).setItemOnCursor(null);
    }

    @Test
    @DisplayName("TransferFreezeService drops leftover cursor item naturally when inventory is full")
    void freezeDropsLeftoverCursorItem() {
        PlayerSessionService sessionService = mock(PlayerSessionService.class);
        JoinLockService joinLockService = mock(JoinLockService.class);
        TransferFreezeService freezeService = new TransferFreezeService(sessionService, joinLockService);

        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        World world = mock(World.class);
        Location loc = mock(Location.class);

        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Tester");
        when(player.isDead()).thenReturn(false);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getInventory()).thenReturn(inv);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(loc);

        ActivePlayerSession session = new ActivePlayerSession(
                uuid, UUID.randomUUID(), "server-1", 1L, 1L, "Tester", SessionState.ACTIVE
        );
        when(sessionService.getLocalSession(uuid)).thenReturn(Optional.of(session));

        ItemStack cursor = mock(ItemStack.class);
        when(cursor.isEmpty()).thenReturn(false);
        when(cursor.getType()).thenReturn(org.bukkit.Material.DIAMOND_SWORD);
        when(player.getItemOnCursor()).thenReturn(cursor);

        ItemStack leftoverItem = mock(ItemStack.class);
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        leftovers.put(0, leftoverItem);
        when(inv.addItem(cursor)).thenReturn(leftovers);

        boolean frozen = freezeService.freeze(player);
        assertThat(frozen).isTrue();

        // Dropped naturally
        verify(world).dropItemNaturally(loc, leftoverItem);
        verify(player).setItemOnCursor(null);
    }

    @Test
    @DisplayName("EnderChestSyncModule.apply gracefully deserializes legacy BukkitObjectInputStream bytes")
    void enderChestAppliesLegacyBukkitObjectStreamWithoutException() throws Exception {
        EnderChestSyncModule module = new EnderChestSyncModule();
        Player player = mock(Player.class);
        Inventory ec = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(ec);

        // Create a serialized Bukkit object stream byte array (magic header 0xAC 0xED)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(null); // writes null or object in Java object stream format
        }
        byte[] legacyBytes = baos.toByteArray();

        byte[][] itemBytes = new byte[27][];
        itemBytes[0] = legacyBytes;
        EnderChestSnapshot snapshot = new EnderChestSnapshot(itemBytes);

        // Must not throw IllegalStateException
        assertDoesNotThrow(() -> module.apply(player, snapshot));
        verify(ec).setContents(any(ItemStack[].class));
    }

    @Test
    @DisplayName("TransferFreezeService stashes multiple leftover items into enderchest and drops remaining to world")
    void freezeStashesIntoEnderChestAndDropsAllLeftovers() {
        PlayerSessionService sessionService = mock(PlayerSessionService.class);
        JoinLockService joinLockService = mock(JoinLockService.class);
        TransferFreezeService freezeService = new TransferFreezeService(sessionService, joinLockService);

        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerInventory inv = mock(PlayerInventory.class);
        Inventory ec = mock(Inventory.class);
        World world = mock(World.class);
        Location loc = mock(Location.class);

        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Tester");
        when(player.isDead()).thenReturn(false);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getInventory()).thenReturn(inv);
        when(player.getEnderChest()).thenReturn(ec);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(loc);

        ActivePlayerSession session = new ActivePlayerSession(
                uuid, UUID.randomUUID(), "server-1", 1L, 1L, "Tester", SessionState.ACTIVE
        );
        when(sessionService.getLocalSession(uuid)).thenReturn(Optional.of(session));

        ItemStack cursor = mock(ItemStack.class);
        when(cursor.isEmpty()).thenReturn(false);
        when(cursor.getType()).thenReturn(org.bukkit.Material.MACE);
        when(player.getItemOnCursor()).thenReturn(cursor);

        ItemStack leftover1 = mock(ItemStack.class);
        when(leftover1.isEmpty()).thenReturn(false);
        ItemStack leftover2 = mock(ItemStack.class);
        when(leftover2.isEmpty()).thenReturn(false);

        HashMap<Integer, ItemStack> invLeftovers = new HashMap<>();
        invLeftovers.put(0, leftover1);
        invLeftovers.put(1, leftover2);
        when(inv.addItem(cursor)).thenReturn(invLeftovers);

        // Ec accepts leftover1, but leftover2 overflows ec as well
        when(ec.addItem(leftover1)).thenReturn(new HashMap<>());
        HashMap<Integer, ItemStack> ecLeftovers = new HashMap<>();
        ecLeftovers.put(0, leftover2);
        when(ec.addItem(leftover2)).thenReturn(ecLeftovers);

        boolean frozen = freezeService.freeze(player);
        assertThat(frozen).isTrue();

        // Verify ec had both items attempted
        verify(ec).addItem(leftover1);
        verify(ec).addItem(leftover2);

        // Verify leftover2 was dropped to world so no item is lost
        verify(world).dropItemNaturally(loc, leftover2);
        verify(player).setItemOnCursor(null);
    }

    @Test
    @DisplayName("EnderChestSyncModule captures and applies 54-slot expanded enderchest without truncation")
    void enderChestSupportsExpanded54SlotCapacity() {
        EnderChestSyncModule module = new EnderChestSyncModule();
        Player player = mock(Player.class);
        Inventory ec = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(ec);
        when(ec.getSize()).thenReturn(54);

        ItemStack itemSlot53 = mock(ItemStack.class);
        when(itemSlot53.isEmpty()).thenReturn(false);
        when(itemSlot53.getType()).thenReturn(org.bukkit.Material.SHULKER_BOX);
        when(itemSlot53.serializeAsBytes()).thenReturn(new byte[]{5, 4, 3, 2, 1});

        ItemStack[] contents = new ItemStack[54];
        contents[53] = itemSlot53;
        when(ec.getContents()).thenReturn(contents);

        EnderChestSnapshot snapshot = module.capture(player);
        assertThat(snapshot.items().length).isEqualTo(54);
        assertThat(snapshot.items()[53]).isEqualTo(new byte[]{5, 4, 3, 2, 1});

        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(new byte[]{5, 4, 3, 2, 1})).thenReturn(itemSlot53);
            module.apply(player, snapshot);
        }

        verify(ec).setContents(argThat(arr -> arr.length == 54 && arr[53] == itemSlot53));
    }
}
