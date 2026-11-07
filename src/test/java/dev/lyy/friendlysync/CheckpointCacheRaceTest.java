package dev.lyy.friendlysync;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CheckpointCacheRaceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitCompletionPreservesMutationsAfterCapture(boolean direct) throws Exception {
        UUID uuid = UUID.randomUUID();
        var sessions = new PlayerSessionService();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 0, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        var inventory = new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
        var enderChest = new EnderChestSnapshot(new byte[27][]);
        session.cacheInventory(inventory);
        session.cacheEnderChest(enderChest);
        session.markDirty();
        var snapshot = new PlayerStateSnapshot(uuid, 0, 1, inventory, enderChest, null, null, null,
            List.of(), null, null, null, null, null, Map.of(), Instant.now(), 3);
        var repository = mock(PlayerStateRepository.class);
        when(repository.commit(any(), any(), anyLong(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
            .thenAnswer(call -> {
                // Gameplay changed both containers while the captured save was in flight.
                session.markInventoryDirty();
                session.markEnderChestDirty();
                session.markDirty();
                return new CommitResult.Success(1, 0);
            });
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(new byte[] {1});
        var modules = mock(SyncModuleManager.class);
        when(modules.captureSnapshot(any(), anyLong(), anyLong(), any(), any())).thenReturn(snapshot);
        var scheduler = mock(PlatformScheduler.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runGlobal(any());
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(scheduler).runAsync(any());
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; }).when(scheduler).runForPlayer(any(), any(), any());
        var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(Runnable::run),
            modules, sessions, scheduler);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(player);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            var save = direct ? service.commitDirect(snapshot, session.toIdentity())
                : service.saveState(uuid, SyncReason.CHECKPOINT, false);
            assertThat(save.get(3, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
        }
        assertThat(session.isDirty()).isTrue();
        assertThat(session.isInventoryDirty()).as("next checkpoint must recapture inventory").isTrue();
        assertThat(session.isEnderChestDirty()).as("next checkpoint must recapture ender chest").isTrue();
    }
}
