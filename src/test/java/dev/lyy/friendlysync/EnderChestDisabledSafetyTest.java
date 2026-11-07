package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.serialization.CompressionAlgorithm;
import dev.lyy.friendlysync.serialization.CompressionService;
import dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.api.SyncReason;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EnderChestDisabledSafetyTest {

    @Test
    @DisplayName("When modules.ender-chest: false, captureSnapshot preserves non-null cached EnderChestSnapshot upon disconnect")
    void disabledModulePreservesExistingEnderChestOnDisconnect() {
        FriendlySyncConfig config = mock(FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
                false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16
        ));
        // Disable ender chest and all other modules for isolated test
        when(config.isModuleEnabled(anyString())).thenReturn(false);
        when(config.isStatisticsCaptureOnFinalSave()).thenReturn(false);

        SyncModuleManager moduleManager = new SyncModuleManager(config);

        UUID uuid = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
                uuid, UUID.randomUUID(), "test-server", 1L, 5L, "TestUser", SessionState.ACTIVE
        );

        // Simulate loaded DB snapshot containing ender chest items
        byte[][] items = new byte[27][];
        items[0] = new byte[]{10, 20, 30};
        items[5] = new byte[]{40, 50, 60};
        EnderChestSnapshot existingEc = new EnderChestSnapshot(items);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("TestUser");
        PlayerInventory playerInv = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(playerInv);
        Inventory ecInv = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(ecInv);

        PlayerStateSnapshot loadedSnapshot = mock(PlayerStateSnapshot.class);
        when(loadedSnapshot.enderChest()).thenReturn(existingEc);

        // Apply snapshot on join caches the ender chest in session even when module is disabled
        moduleManager.applySnapshot(player, loadedSnapshot, session);
        assertThat(session.getCachedEnderChest()).isPresent();
        assertThat(session.getCachedEnderChest().get()).isEqualTo(existingEc);

        // Verify ender chest was NOT applied to in-game inventory because module is disabled
        verify(ecInv, never()).setContents(any());

        // Player quits -> capture snapshot for quit
        PlayerStateSnapshot quitSnapshot = moduleManager.captureSnapshot(player, 5L, 1L, SyncReason.QUIT, session);

        // The snapshot MUST NOT have null enderChest! It must preserve the non-null snapshot from DB
        assertThat(quitSnapshot.enderChest()).isNotNull();
        assertThat(quitSnapshot.enderChest().items()[0]).isEqualTo(new byte[]{10, 20, 30});
        assertThat(quitSnapshot.enderChest().items()[5]).isEqualTo(new byte[]{40, 50, 60});

        // Verify that serialization preserves it through round-trip
        DefaultPlayerStateSerializer serializer = new DefaultPlayerStateSerializer(
                new CompressionService(CompressionAlgorithm.NONE, 0)
        );
        byte[] serialized = serializer.serialize(quitSnapshot);
        PlayerStateSnapshot deserialized = serializer.deserialize(serialized);

        assertThat(deserialized.enderChest()).isNotNull();
        assertThat(deserialized.enderChest().items()[0]).isEqualTo(new byte[]{10, 20, 30});
        assertThat(deserialized.enderChest().items()[5]).isEqualTo(new byte[]{40, 50, 60});
    }

    @Test
    @DisplayName("When modules.ender-chest: true, captureSnapshot captures live contents from player")
    void enabledModuleCapturesLivePlayerContents() {
        FriendlySyncConfig config = mock(FriendlySyncConfig.class);
        when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
                false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16
        ));
        when(config.isModuleEnabled(anyString())).thenReturn(false);
        when(config.isModuleEnabled("ender-chest")).thenReturn(true);
        when(config.isStatisticsCaptureOnFinalSave()).thenReturn(false);

        SyncModuleManager moduleManager = new SyncModuleManager(config);

        UUID uuid = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
                uuid, UUID.randomUUID(), "test-server", 1L, 1L, "TestUser", SessionState.ACTIVE
        );

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("TestUser");
        PlayerInventory playerInv = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(playerInv);
        Inventory ecInv = mock(Inventory.class);
        when(player.getEnderChest()).thenReturn(ecInv);
        when(ecInv.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[27]);

        PlayerStateSnapshot snapshot = moduleManager.captureSnapshot(player, 1L, 1L, SyncReason.QUIT, session);
        assertThat(snapshot.enderChest()).isNotNull();
        assertThat(snapshot.enderChest().items().length).isEqualTo(27);
    }
}
