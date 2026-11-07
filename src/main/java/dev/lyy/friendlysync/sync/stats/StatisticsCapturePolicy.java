package dev.lyy.friendlysync.sync.stats;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure policy for rate-limiting expensive full statistics scans.
 */
public final class StatisticsCapturePolicy {

    private StatisticsCapturePolicy() {
    }

    public static boolean checkpointRefreshDue(
        Instant lastCapturedAt,
        Duration minimumInterval,
        Instant now
    ) {
        if (lastCapturedAt == null) {
            return true;
        }
        Duration interval = minimumInterval != null && !minimumInterval.isNegative()
            ? minimumInterval
            : Duration.ZERO;
        Instant currentTime = now != null ? now : Instant.now();
        return !lastCapturedAt.plus(interval).isAfter(currentTime);
    }
}
