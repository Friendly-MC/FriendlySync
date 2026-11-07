package dev.lyy.friendlysync.sync.stats;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashSet;

/**
 * Service for authoritatively applying a StatisticsSnapshot to a live player.
 * Ensures exact authoritative replacement without additive increments.
 * Must execute on the player's owning thread.
 */
public final class StatisticsApplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsApplyService.class);

    private final StatisticsRegistry registry;
    private final StatisticsCaptureService captureService;

    public StatisticsApplyService(StatisticsRegistry registry) {
        this(registry, new StatisticsCaptureService(registry));
    }

    public StatisticsApplyService(StatisticsRegistry registry, StatisticsCaptureService captureService) {
        this.registry = Preconditions.checkNotNull(registry, "registry cannot be null");
        this.captureService = Preconditions.checkNotNull(captureService, "captureService cannot be null");
    }

    public void apply(Player player, StatisticsSnapshot snapshot) {
        Preconditions.checkNotNull(player, "player cannot be null");
        if (snapshot == null) {
            snapshot = StatisticsSnapshot.EMPTY;
        }

        Map<StatisticKey, Long> targetValues = snapshot.values();
        Map<StatisticKey, Long> currentValues = captureService.capture(player).values();
        var keys = new HashSet<>(currentValues.keySet());
        keys.addAll(targetValues.keySet());

        // Sparse snapshots omit zeroes. Clear stale destination values without writing unchanged statistics.
        for (StatisticKey key : keys) {
            StatisticsRegistry.StatisticDescriptor desc = registry.findByKey(key);
            if (desc == null || captureService.isKeyUnsupported(key)) {
                continue;
            }

            long rawVal = targetValues.getOrDefault(key, 0L);
            int clampedVal = (int) Math.min(Integer.MAX_VALUE, Math.max(0, rawVal));
            if (currentValues.getOrDefault(key, 0L) == clampedVal) {
                continue;
            }

            try {
                switch (desc.type()) {
                    case UNTYPED -> player.setStatistic(desc.statistic(), clampedVal);
                    case BLOCK, ITEM -> player.setStatistic(desc.statistic(), desc.material(), clampedVal);
                    case ENTITY -> player.setStatistic(desc.statistic(), desc.entityType(), clampedVal);
                }
            } catch (IllegalArgumentException | UnsupportedOperationException t) {
                LOGGER.warn("[STATS] Failed to apply statistic {}: {}", desc.key(), t.getMessage());
            }
        }
    }
}
