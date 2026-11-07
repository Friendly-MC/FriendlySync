package dev.lyy.friendlysync;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.SessionState;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConcurrentCaptureTest {
    @Test void independentRegionsCaptureConcurrentlyAndShutdownKeepsRunningSaveOrdered() throws Exception {
        var sessions = new PlayerSessionService();
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        var first = new ActivePlayerSession(firstId, UUID.randomUUID(), "spawn-a", 1, 0, "First", SessionState.ACTIVE);
        var second = new ActivePlayerSession(secondId, UUID.randomUUID(), "spawn-a", 1, 0, "Second", SessionState.ACTIVE);
        sessions.registerSession(first);
        sessions.registerSession(second);
        var firstPlayer = mock(Player.class);
        var secondPlayer = mock(Player.class);
        when(firstPlayer.isOnline()).thenReturn(true);
        when(secondPlayer.isOnline()).thenReturn(true);
        var modules = mock(SyncModuleManager.class);
        var repository = mock(PlayerStateRepository.class);
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(new byte[]{1, 2, 3});
        when(repository.commit(any(), any(), anyLong(), anyLong(), any(), anyInt(), anyString(), anyString(), anyString()))
            .thenAnswer(call -> new CommitResult.Success(call.getArgument(3, Long.class) + 1, 0));
        var enteredFirst = new CountDownLatch(1);
        var enteredSecond = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        when(modules.captureSnapshot(eq(firstPlayer), anyLong(), anyLong(), any(), eq(first))).thenAnswer(call -> {
            enteredFirst.countDown();
            if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("Capture was not released");
            return snapshot(firstId);
        });
        when(modules.captureSnapshot(eq(secondPlayer), anyLong(), anyLong(), any(), eq(second))).thenAnswer(call -> {
            enteredSecond.countDown();
            return snapshot(secondId);
        });
        var scheduler = mock(PlatformScheduler.class);
        var global = new LinkedBlockingQueue<Runnable>();
        doAnswer(call -> { global.add(call.getArgument(0)); return null; }).when(scheduler).runGlobal(any());
        var workers = Executors.newFixedThreadPool(4);
        var regions = Executors.newFixedThreadPool(2);
        try {
            doAnswer(call -> {
                Runnable capture = call.getArgument(1);
                regions.submit(() -> {
                    try (var bukkit = mockStatic(Bukkit.class)) {
                        bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
                        capture.run();
                    }
                });
                return null;
            }).when(scheduler).runForPlayer(any(), any(), any());
            var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(workers),
                modules, sessions, scheduler, workers, null);
            var firstSave = service.saveState(firstId, SyncReason.MANUAL, true);
            runGlobal(global, firstId, firstPlayer, secondId, secondPlayer);
            assertThat(enteredFirst.await(2, TimeUnit.SECONDS)).isTrue();
            var secondSave = service.saveState(secondId, SyncReason.MANUAL, true);
            runGlobal(global, firstId, firstPlayer, secondId, secondPlayer);
            assertThat(enteredSecond.await(2, TimeUnit.SECONDS)).as("second region must not wait for first capture").isTrue();
            assertThat(secondSave.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            workers.submit(service::beginShutdown).get(2, TimeUnit.SECONDS);
            assertThat(firstSave.isDone()).as("shutdown must not cancel an already running capture").isFalse();
            var finalSave = service.commitDirect(snapshot(firstId), first.toIdentity());
            assertThat(finalSave.isDone()).isFalse();
            releaseFirst.countDown();
            assertThat(firstSave.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            assertThat(finalSave.get(2, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            assertThat(first.getRevision()).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
            regions.shutdownNow();
            workers.shutdownNow();
            regions.awaitTermination(3, TimeUnit.SECONDS);
            workers.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private void runGlobal(BlockingQueue<Runnable> global, UUID firstId, Player first, UUID secondId, Player second) throws Exception {
        Runnable task = global.poll(2, TimeUnit.SECONDS);
        assertThat(task).isNotNull();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(firstId)).thenReturn(first);
            bukkit.when(() -> Bukkit.getPlayer(secondId)).thenReturn(second);
            task.run();
        }
    }

    private PlayerStateSnapshot snapshot(UUID uuid) {
        return new PlayerStateSnapshot(uuid, 0, 1, null, null, null, null, null,
            List.of(), null, null, null, null, null, Map.of(), Instant.now(), 3);
    }
}
