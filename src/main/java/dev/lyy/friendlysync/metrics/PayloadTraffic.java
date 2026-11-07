package dev.lyy.friendlysync.metrics;

import java.util.function.LongSupplier;

/** Application payload counts, excluding JDBC protocol, replication, Redis and game packets. */
public final class PayloadTraffic {
    private final LongSupplier nanoTime;
    private final long[] seconds = new long[60];
    private final long[] uploads = new long[60];
    private final long[] downloads = new long[60];
    private long totalUpload;
    private long totalDownload;
    private long largestPayload;

    public PayloadTraffic() {
        this(System::nanoTime);
    }

    public PayloadTraffic(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        java.util.Arrays.fill(seconds, Long.MIN_VALUE);
    }

    public synchronized void recordUpload(int bytes) {
        record(bytes, true);
    }

    public synchronized void recordDownload(int bytes) {
        record(bytes, false);
    }

    private void record(int bytes, boolean upload) {
        if (bytes <= 0) return;
        long second = Math.floorDiv(nanoTime.getAsLong(), 1_000_000_000L);
        int slot = Math.floorMod(second, seconds.length);
        if (seconds[slot] != second) {
            seconds[slot] = second;
            uploads[slot] = downloads[slot] = 0;
        }
        if (upload) {
            uploads[slot] += bytes;
            totalUpload += bytes;
        } else {
            downloads[slot] += bytes;
            totalDownload += bytes;
        }
        largestPayload = Math.max(largestPayload, bytes);
    }

    public synchronized Snapshot snapshot() {
        long now = Math.floorDiv(nanoTime.getAsLong(), 1_000_000_000L);
        long upload = 0, download = 0;
        for (int i = 0; i < seconds.length; i++) {
            if (seconds[i] != Long.MIN_VALUE && now - seconds[i] >= 0 && now - seconds[i] < 60) {
                upload += uploads[i];
                download += downloads[i];
            }
        }
        return new Snapshot(upload, download, totalUpload, totalDownload, largestPayload);
    }

    public record Snapshot(long recentUploadBytes, long recentDownloadBytes,
                           long totalUploadBytes, long totalDownloadBytes, long largestPayloadBytes) {}
}
