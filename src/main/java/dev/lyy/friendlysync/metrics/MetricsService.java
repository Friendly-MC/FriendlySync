package dev.lyy.friendlysync.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks internal performance metrics, commit counts, and latency averages.
 */
public final class MetricsService {

    private final AtomicLong totalSaves = new AtomicLong(0);
    private final AtomicLong totalLoads = new AtomicLong(0);
    private final AtomicLong totalConflicts = new AtomicLong(0);
    private final AtomicLong totalSaveDurationMs = new AtomicLong(0);

    public void recordSave(long durationMs) {
        totalSaves.incrementAndGet();
        totalSaveDurationMs.addAndGet(durationMs);
    }

    public void recordLoad() {
        totalLoads.incrementAndGet();
    }

    public void recordConflict() {
        totalConflicts.incrementAndGet();
    }

    public long getTotalSaves() {
        return totalSaves.get();
    }

    public long getTotalLoads() {
        return totalLoads.get();
    }

    public long getTotalConflicts() {
        return totalConflicts.get();
    }

    public double getAverageSaveLatencyMs() {
        long saves = totalSaves.get();
        return saves > 0 ? (double) totalSaveDurationMs.get() / saves : 0.0;
    }
}
