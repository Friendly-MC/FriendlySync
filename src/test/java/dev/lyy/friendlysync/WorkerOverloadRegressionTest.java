package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.PlayerStateMetadata;
import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.*;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.*;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerOverloadRegressionTest {
    @TempDir Path directory;

    @Test
    void rejectedInitialDispatchSettlesFutureAndRetiresQueue() {
        var queue = new PlayerOperationQueue(task -> { throw new RejectedExecutionException("full"); });
        var result = queue.submitRunnable(UUID.randomUUID(), () -> fail("Rejected work must not run"));
        assertThat(result).isCompletedExceptionally();
        assertThat(queue.getActiveQueueCount()).isZero();
    }

    @Test
    void rejectedContinuationFailsAllWaitingTasksAndLaterSubmissionsRecover() throws Exception {
        ArrayDeque<Runnable> dispatches = new ArrayDeque<>();
        AtomicBoolean reject = new AtomicBoolean();
        var queue = new PlayerOperationQueue(task -> {
            if (reject.get()) throw new RejectedExecutionException("full");
            dispatches.add(task);
        });
        UUID uuid = UUID.randomUUID();
        CompletableFuture<String> firstWork = new CompletableFuture<>();
        var first = queue.submit(uuid, () -> firstWork);
        dispatches.remove().run();
        var second = queue.submitRunnable(uuid, () -> fail("Rejected continuation must not run"));
        var third = queue.submitRunnable(uuid, () -> fail("Rejected continuation must not run"));
        reject.set(true);
        firstWork.complete("saved");
        assertThat(first.join()).isEqualTo("saved");
        assertThat(second).isCompletedExceptionally();
        assertThat(third).isCompletedExceptionally();
        assertThat(queue.getActiveQueueCount()).isZero();
        reject.set(false);
        var retry = queue.submit(uuid, () -> CompletableFuture.completedFuture("retry"));
        dispatches.remove().run();
        assertThat(retry.get(1, TimeUnit.SECONDS)).isEqualTo("retry");
    }

    @Test
    void overloadRunsBackgroundCompletionsInlineButRejectsTickWorkAndShutdownWork() throws Exception {
        Thread tick = Thread.currentThread();
        var worker = FriendlySyncPlugin.createWorkerExecutor(1, 1, Thread::new, () -> Thread.currentThread() == tick);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            worker.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            worker.execute(() -> {});
            assertThatThrownBy(() -> worker.execute(() -> fail("Tick work must not run inline")))
                .isInstanceOf(RejectedExecutionException.class);
            CompletableFuture.runAsync(() -> {
                Thread background = Thread.currentThread();
                worker.execute(() -> assertThat(Thread.currentThread()).isSameAs(background));
            }).get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown(); worker.shutdown(); worker.awaitTermination(2, TimeUnit.SECONDS);
        }
        assertThatThrownBy(() -> worker.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void boundedPoolOverloadRetainsFinalCaptureAndRecoversWithOriginalFenceAndRevision() throws Exception {
        UUID uuid = UUID.randomUUID();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 7, 12, "Player", SessionState.ACTIVE);
        var sessions = new PlayerSessionService();
        sessions.registerSession(session);
        var repository = mock(PlayerStateRepository.class);
        byte[] bytes = {1, 2, 3};
        String hash = DigestUtils.sha256(bytes);
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.serialize(any())).thenReturn(bytes);
        when(repository.findMetadata(uuid)).thenReturn(Optional.of(new PlayerStateMetadata(12, 7, session.getSessionId(), "source", null)),
            Optional.of(new PlayerStateMetadata(13, 7, session.getSessionId(), "source", hash)));
        when(repository.commit(eq(uuid), eq(session.getSessionId()), eq(7L), eq(12L), any(), anyInt(), eq(hash), eq("FINAL_QUIT"), eq("source")))
            .thenReturn(new CommitResult.Success(13, 1));
        when(repository.releaseOwnership(uuid, session.getSessionId(), 7, "OFFLINE")).thenReturn(true);
        Thread tick = Thread.currentThread();
        var worker = FriendlySyncPlugin.createWorkerExecutor(1, 1, Thread::new, () -> Thread.currentThread() == tick);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        try {
            worker.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            worker.execute(drained::countDown);
            var service = new PlayerStateService(repository, serializer, new PlayerOperationQueue(worker),
                mock(SyncModuleManager.class), sessions, mock(PlatformScheduler.class), worker, null);
            service.enableFinalSaveJournal(directory, "source");
            var snapshot = new PlayerStateSnapshot(uuid, 12, 7, null, null, null, null, null, List.of(), null, null,
                null, null, null, Map.of(), Instant.now(), PlayerStateSnapshot.CURRENT_SCHEMA_VERSION);
            assertThat(service.commitDirect(snapshot, session.toIdentity()).get(1, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Failed.class);
            assertThat(service.hasPendingFinalSave(uuid)).isTrue();
            assertThat(service.hasUnrecoveredFinalSaves()).isTrue();
            verifyNoInteractions(serializer, repository);
            // The quit path removes its local session while retaining the original SQL fence.
            sessions.removeSession(session.toIdentity());
            release.countDown();
            assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.flushPendingFinalSave(uuid).get(3, TimeUnit.SECONDS)).isInstanceOf(CommitResult.Success.class);
            assertThat(service.hasPendingFinalSave(uuid)).isFalse();
            verify(serializer).serialize(snapshot);
            verify(repository).releaseOwnership(uuid, session.getSessionId(), 7, "OFFLINE");
        } finally {
            release.countDown(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
