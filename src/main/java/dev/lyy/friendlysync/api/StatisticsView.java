package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view of a player's synchronized statistics.
 */
public record StatisticsView(StatisticsSnapshot snapshot) {

    public StatisticsView(StatisticsSnapshot snapshot) {
        this.snapshot = snapshot != null ? snapshot : StatisticsSnapshot.EMPTY;
    }

    public int size() {
        return snapshot.size();
    }

    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    public Set<StatisticKey> getKeys() {
        return snapshot.values().keySet();
    }

    public long get(StatisticKey key) {
        return snapshot.getOrDefault(key, 0L);
    }

    public Map<StatisticKey, Long> asMap() {
        return Collections.unmodifiableMap(snapshot.values());
    }
}
