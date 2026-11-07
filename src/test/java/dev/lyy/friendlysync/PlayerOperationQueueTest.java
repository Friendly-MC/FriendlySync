package dev.lyy.friendlysync;

import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlayerOperationQueueTest {

    private ExecutorService executor;
    private PlayerOperationQueue queue;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(4);
        queue = new PlayerOperationQueue(executor, 10);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        executor.awaitTermination(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Operation Queue: Tasks for the same player UUID execute strictly in FIFO order")
    void testPerPlayerFifoExecution() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        List<Integer> executionOrder = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<?> f1 = queue.submit(playerUuid, () -> CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {}
            executionOrder.add(1);
        }, executor));

        CompletableFuture<?> f2 = queue.submit(playerUuid, () -> CompletableFuture.runAsync(() -> {
            executionOrder.add(2);
        }, executor));

        CompletableFuture<?> f3 = queue.submit(playerUuid, () -> CompletableFuture.runAsync(() -> {
            executionOrder.add(3);
        }, executor));

        CompletableFuture.allOf(f1, f2, f3).get(3, TimeUnit.SECONDS);

        assertThat(executionOrder).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("Operation Queue: Saturated queue rejects new tasks with backpressure exception")
    void testBackpressureRejection() {
        UUID playerUuid = UUID.randomUUID();
        PlayerOperationQueue tightQueue = new PlayerOperationQueue(executor, 4);

        // Submit tasks that block briefly until capacity is exceeded
        boolean rejected = false;
        for (int i = 0; i < 10; i++) {
            CompletableFuture<?> future = tightQueue.submit(playerUuid, () -> CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
            }, executor));
            if (future.isCompletedExceptionally()) {
                rejected = true;
                break;
            }
        }

        assertThat(rejected).isTrue();
    }
}
