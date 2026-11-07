package dev.lyy.friendlysync;

import dev.lyy.friendlysync.metrics.PayloadTraffic;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class PayloadTrafficTest {
    @Test void expiresOldTrafficWithoutLosingLifetimeTotals() {
        var clock = new AtomicLong(-Duration.ofSeconds(10).toNanos());
        var traffic = new PayloadTraffic(clock::get);
        traffic.recordUpload(128 * 1024);
        traffic.recordDownload(256 * 1024);
        assertThat(traffic.snapshot().recentUploadBytes()).isEqualTo(128 * 1024);
        clock.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(traffic.snapshot().recentDownloadBytes()).isEqualTo(256 * 1024);
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        traffic.recordUpload(1024);
        var result = traffic.snapshot();
        assertThat(result.recentUploadBytes()).isEqualTo(1024);
        assertThat(result.recentDownloadBytes()).isZero();
        assertThat(result.totalUploadBytes()).isEqualTo(129 * 1024);
        assertThat(result.totalDownloadBytes()).isEqualTo(256 * 1024);
        assertThat(result.largestPayloadBytes()).isEqualTo(256 * 1024);
    }

    @Test void concurrentTransfersKeepExactCounts() throws Exception {
        var traffic = new PayloadTraffic(() -> 0L);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++) tasks.add(workers.submit(() -> {
                traffic.recordUpload(131072);
                traffic.recordDownload(8192);
            }));
            for (var task : tasks) task.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(traffic.snapshot().totalUploadBytes()).isEqualTo(40L * 131072);
        assertThat(traffic.snapshot().totalDownloadBytes()).isEqualTo(40L * 8192);
    }
}
