package dev.lyy.friendlysync.scheduler;

import dev.lyy.friendlysync.util.Preconditions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Bounded FIFO asynchronous operations per player, with explicit overload failures. */
public final class PlayerOperationQueue {
    private final Executor workerExecutor;
    private final int maxQueueDepth;
    private final ConcurrentHashMap<UUID, PlayerQueue> queues = new ConcurrentHashMap<>();

    public PlayerOperationQueue(Executor workerExecutor) {
        this(workerExecutor, 32);
    }

    public PlayerOperationQueue(Executor workerExecutor, int maxQueueDepth) {
        this.workerExecutor = Preconditions.checkNotNull(workerExecutor, "workerExecutor cannot be null");
        this.maxQueueDepth = Math.max(4, maxQueueDepth);
    }

    public <T> CompletableFuture<T> submit(UUID uuid, Supplier<CompletableFuture<T>> task) {
        Preconditions.checkNotNull(uuid, "uuid cannot be null");
        Preconditions.checkNotNull(task, "task cannot be null");
        CompletableFuture<T> result = new CompletableFuture<>();
        PlayerQueue queue;
        boolean start;
        while (true) {
            queue = queues.computeIfAbsent(uuid, PlayerQueue::new);
            synchronized (queue) {
                if (queues.get(uuid) != queue) continue;
                if (queue.tasks.size() >= maxQueueDepth) {
                    return CompletableFuture.failedFuture(new RejectedExecutionException("Player operation queue saturated for " + uuid));
                }
                queue.tasks.add(new PendingTask(() -> {
                    try {
                        CompletableFuture<T> inner = task.get();
                        if (inner == null) {
                            result.complete(null);
                            return CompletableFuture.completedFuture(null);
                        }
                        inner.whenComplete((value, error) -> {
                            if (error == null) result.complete(value);
                            else result.completeExceptionally(error);
                        });
                        return inner;
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                        return CompletableFuture.failedFuture(error);
                    }
                }, result));
                start = !queue.running;
                queue.running = true;
                break;
            }
        }
        if (start) scheduleNext(queue);
        return result;
    }

    public CompletableFuture<Void> submitRunnable(UUID uuid, Runnable task) {
        return submit(uuid, () -> {
            task.run(); // Already on the worker: avoid a second dispatch to the same pool.
            return CompletableFuture.completedFuture(null);
        });
    }

    private void scheduleNext(PlayerQueue queue) {
        synchronized (queue) {
            if (queue.tasks.isEmpty()) {
                queue.running = false;
                queues.remove(queue.uuid, queue);
                return;
            }
        }
        try {
            workerExecutor.execute(() -> {
                PendingTask next;
                synchronized (queue) {
                    next = queue.tasks.poll();
                }
                if (next != null) next.start().get().whenComplete((ignored, error) -> scheduleNext(queue));
            });
        } catch (RejectedExecutionException error) {
            // Remove the queue before invoking callbacks. All admitted futures must settle,
            // and later submissions must be able to start a fresh queue after recovery.
            var rejected = new ArrayList<PendingTask>();
            synchronized (queue) {
                rejected.addAll(queue.tasks);
                queue.tasks.clear();
                queue.running = false;
                queues.remove(queue.uuid, queue);
            }
            rejected.forEach(task -> task.result().completeExceptionally(error));
        }
    }

    public int getActiveQueueCount() {
        return queues.size();
    }

    public int getQueueDepth(UUID uuid) {
        PlayerQueue queue = queues.get(uuid);
        if (queue == null) return 0;
        synchronized (queue) {
            return queue.tasks.size();
        }
    }

    private record PendingTask(Supplier<CompletableFuture<?>> start, CompletableFuture<?> result) {}

    private static final class PlayerQueue {
        private final UUID uuid;
        private final Queue<PendingTask> tasks = new ArrayDeque<>();
        private boolean running;

        private PlayerQueue(UUID uuid) {
            this.uuid = uuid;
        }
    }
}
