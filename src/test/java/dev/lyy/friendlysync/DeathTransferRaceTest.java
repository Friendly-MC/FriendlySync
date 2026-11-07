package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.listener.PlayerDeathListener;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.TransferResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.sync.module.InventorySyncModule;
import dev.lyy.friendlysync.transfer.PlayerTransferService;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DeathTransferRaceTest {

    @Test
    void deathSnapshotUsesFinalKeepLevelEvenWhenInventoryAndWorldRuleDisagree() {
        for (boolean worldKeep : new boolean[]{false, true}) {
            for (boolean inventoryKeep : new boolean[]{false, true}) {
                for (boolean levelKeep : new boolean[]{false, true}) {
                    UUID uuid = UUID.randomUUID();
                    Player player = Mockito.mock(Player.class);
                    World world = Mockito.mock(World.class);
                    Mockito.when(player.getUniqueId()).thenReturn(uuid);
                    Mockito.when(player.isDead()).thenReturn(true);
                    Mockito.when(player.getWorld()).thenReturn(world);
                    Mockito.when(world.getGameRuleValue(GameRule.KEEP_INVENTORY)).thenReturn(worldKeep);
                    Mockito.when(player.getLevel()).thenReturn(37);
                    Mockito.when(player.getExp()).thenReturn(0.25f);
                    Mockito.when(player.getTotalExperience()).thenReturn(700);
                    PlayerDeathEvent death = Mockito.mock(PlayerDeathEvent.class);
                    Mockito.when(death.getEntity()).thenReturn(player);
                    Mockito.when(death.getKeepInventory()).thenReturn(inventoryKeep);
                    Mockito.when(death.getKeepLevel()).thenReturn(levelKeep);
                    var listener = new PlayerDeathListener(Mockito.mock(DirtyTrackingService.class),
                        Mockito.mock(PlatformScheduler.class), new PlayerSessionService());
                    try {
                        listener.onPlayerDeath(death);
                        var captured = new dev.lyy.friendlysync.sync.module.ExperienceSyncModule().capture(player);
                        assertThat(captured.level()).isEqualTo(levelKeep ? 37 : 0);
                        assertThat(captured.expProgress()).isEqualTo(levelKeep ? 0.25f : 0.0f);
                        assertThat(captured.totalExperience()).isEqualTo(levelKeep ? 700 : 0);
                        var respawn = Mockito.mock(org.bukkit.event.player.PlayerRespawnEvent.class);
                        Mockito.when(respawn.getPlayer()).thenReturn(player);
                        listener.onPlayerRespawn(respawn);
                        assertThat(PlayerDeathListener.hasKeepLevel(uuid, worldKeep)).isEqualTo(worldKeep);
                    } finally {
                        PlayerDeathListener.clear(uuid);
                    }
                }
            }
        }
    }

    @Test
    void cancelledDeathDoesNotRecordRetentionOrDirtyState() {
        UUID uuid = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        PlayerDeathEvent death = Mockito.mock(PlayerDeathEvent.class);
        Mockito.when(death.getEntity()).thenReturn(player);
        Mockito.when(death.isCancelled()).thenReturn(true);
        var dirty = Mockito.mock(DirtyTrackingService.class);
        var scheduler = Mockito.mock(PlatformScheduler.class);
        var listener = new PlayerDeathListener(dirty, scheduler, new PlayerSessionService());
        try {
            listener.onPlayerDeath(death);
            assertThat(PlayerDeathListener.hasKeepLevel(uuid, true)).isTrue();
            assertThat(PlayerDeathListener.hasKeepLevel(uuid, false)).isFalse();
            Mockito.verifyNoInteractions(dirty, scheduler);
        } finally {
            PlayerDeathListener.clear(uuid);
        }
    }

    @Test
    @DisplayName("Death Transfer Race: Death drops are suppressed if player is currently in TRANSFER_PENDING state")
    void testDeathDropsSuppressedDuringTransferPending() {
        UUID playerUuid = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerUuid);
        Mockito.when(player.getName()).thenReturn("Steve");

        PlayerSessionService sessionService = new PlayerSessionService();
        ActivePlayerSession session = sessionService.registerSession(
            new dev.lyy.friendlysync.model.SessionIdentity(playerUuid, UUID.randomUUID(), "server-1", 100L),
            1, "Steve", SessionState.TRANSFER_PENDING
        );

        DirtyTrackingService dirtyTrackingService = Mockito.mock(DirtyTrackingService.class);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);

        PlayerDeathListener listener = new PlayerDeathListener(dirtyTrackingService, scheduler, sessionService);

        List<ItemStack> drops = new ArrayList<>();
        ItemStack droppedDiamond = Mockito.mock(ItemStack.class);
        drops.add(droppedDiamond);

        PlayerDeathEvent deathEvent = Mockito.mock(PlayerDeathEvent.class);
        Mockito.when(deathEvent.getEntity()).thenReturn(player);
        Mockito.when(deathEvent.getDrops()).thenReturn(drops);

        // Run early death event handler
        listener.onPlayerDeathEarly(deathEvent);

        // Drops and exp MUST be cleared to prevent duplicating items that were already committed to the target server
        assertThat(drops).isEmpty();
        Mockito.verify(deathEvent).setDroppedExp(0);
    }

    @Test
    @DisplayName("Death Transfer Race: InventorySyncModule captures EMPTY inventory if player is dead and keepInventory is false")
    void testDeadPlayerCapturesEmptyInventory() {
        Player player = Mockito.mock(Player.class);
        World world = Mockito.mock(World.class);

        Mockito.when(player.isDead()).thenReturn(true);
        Mockito.when(player.getWorld()).thenReturn(world);
        Mockito.when(world.getGameRuleValue(GameRule.KEEP_INVENTORY)).thenReturn(Boolean.FALSE);

        InventorySyncModule module = new InventorySyncModule();
        InventorySnapshot snapshot = module.capture(player);

        // Every storage slot must be null (empty)
        assertThat(snapshot.storage().length).isEqualTo(36);
        for (byte[] slot : snapshot.storage()) {
            assertThat(slot).isNull();
        }
        assertThat(snapshot.armor().length).isEqualTo(4);
        for (byte[] slot : snapshot.armor()) {
            assertThat(slot).isNull();
        }
        assertThat(snapshot.offHand()).isNull();
        assertThat(snapshot.cursor()).isNull();
    }

    @Test
    @DisplayName("Death Transfer Race: PlayerTransferService rejects transfer when player is dead")
    void testTransferRejectedWhenPlayerIsDead() {
        UUID playerUuid = UUID.randomUUID();
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerUuid);
        Mockito.when(player.isDead()).thenReturn(true);

        PlayerSessionService sessionService = new PlayerSessionService();
        ActivePlayerSession session = sessionService.registerSession(
            new dev.lyy.friendlysync.model.SessionIdentity(playerUuid, UUID.randomUUID(), "server-1", 100L),
            1, "Steve", SessionState.ACTIVE
        );

        Plugin plugin = Mockito.mock(Plugin.class);
        FriendlySyncConfig config = Mockito.mock(FriendlySyncConfig.class);
        PlatformScheduler scheduler = Mockito.mock(PlatformScheduler.class);
        PlayerOperationQueue queue = Mockito.mock(PlayerOperationQueue.class);
        PlayerOwnershipService ownershipService = Mockito.mock(PlayerOwnershipService.class);
        SyncModuleManager syncModuleManager = Mockito.mock(SyncModuleManager.class);
        PlayerStateSerializer serializer = Mockito.mock(PlayerStateSerializer.class);
        dev.lyy.friendlysync.database.repository.PlayerStateRepository stateRepo = Mockito.mock(dev.lyy.friendlysync.database.repository.PlayerStateRepository.class);
        dev.lyy.friendlysync.database.repository.TransferLogRepository transferRepo = Mockito.mock(dev.lyy.friendlysync.database.repository.TransferLogRepository.class);
        dev.lyy.friendlysync.messaging.MessagingService messagingService = Mockito.mock(dev.lyy.friendlysync.messaging.MessagingService.class);
        JoinLockService joinLockService = Mockito.mock(JoinLockService.class);
        TransferFreezeService freezeService = Mockito.mock(TransferFreezeService.class);

        PlayerTransferService transferService = new PlayerTransferService(
            plugin, config, scheduler, queue, sessionService, ownershipService, syncModuleManager,
            Mockito.mock(dev.lyy.friendlysync.session.PlayerStateService.class), transferRepo, messagingService, freezeService, joinLockService
        );

        TransferResult result = transferService.transfer(player, "server-2").join();
        assertThat(result).isInstanceOf(TransferResult.Failed.class);
        assertThat(((TransferResult.Failed) result).reason()).contains("dead");
    }

    @Test
    @DisplayName("Death Transfer Race: TransferFreezeService rejects freezing dead player")
    void testFreezeRejectsDeadPlayer() {
        Player player = Mockito.mock(Player.class);
        Mockito.when(player.isDead()).thenReturn(true);
        Mockito.when(player.getName()).thenReturn("Steve");

        PlayerSessionService sessionService = new PlayerSessionService();
        JoinLockService joinLockService = Mockito.mock(JoinLockService.class);

        TransferFreezeService freezeService = new TransferFreezeService(sessionService, joinLockService);

        boolean frozen = freezeService.freeze(player);
        assertThat(frozen).isFalse();
    }
}
