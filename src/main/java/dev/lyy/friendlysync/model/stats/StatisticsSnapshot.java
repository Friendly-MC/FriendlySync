package dev.lyy.friendlysync.model.stats;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of a player's synchronized statistics.
 */
public record StatisticsSnapshot(Map<StatisticKey, Long> values) {

    public static final StatisticsSnapshot EMPTY = new StatisticsSnapshot(Collections.emptyMap());

    public StatisticsSnapshot {
        values = values != null ? Map.copyOf(values) : Collections.emptyMap();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    public long getOrDefault(StatisticKey key, long defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }
}
