package dev.lyy.friendlysync.sync.stats;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for capturing an immutable StatisticsSnapshot from a live player.
 * Must execute on the player's owning thread.
 */
public final class StatisticsCaptureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsCaptureService.class);

    // Non-empty native reads that must match the API sweep before the sweep is skipped.
    private static final int NATIVE_VALIDATIONS = 3;

    private final StatisticsRegistry registry;
    private final Set<StatisticKey> unsupportedKeys = ConcurrentHashMap.newKeySet();
    private volatile StatisticsRegistry.StatisticDescriptor[] activeDescriptors;
    private final NativeStatisticsReader nativeReader;
    private final AtomicInteger nativeValidationsLeft = new AtomicInteger(NATIVE_VALIDATIONS);
    private volatile boolean nativeDisabled;

    public StatisticsCaptureService(StatisticsRegistry registry) {
        this.registry = Preconditions.checkNotNull(registry, "registry cannot be null");
        this.activeDescriptors = registry.allDescriptors().toArray(new StatisticsRegistry.StatisticDescriptor[0]);
        this.nativeReader = new NativeStatisticsReader(registry);
    }

    public StatisticsSnapshot capture(Player player) {
        Preconditions.checkNotNull(player, "player cannot be null");

        if (nativeDisabled) {
            return sweep(player);
        }
        if (nativeValidationsLeft.get() <= 0) {
            Map<StatisticKey, Long> fast = readNative(player);
            return fast != null ? new StatisticsSnapshot(fast) : sweep(player);
        }

        // Validation phase: the sweep stays authoritative until the native read has matched it.
        StatisticsSnapshot swept = sweep(player);
        Map<StatisticKey, Long> fast = readNative(player);
        if (fast != null) {
            if (!fast.equals(swept.values())) {
                disableNative("native read disagrees with API sweep " + describeMismatch(fast, swept.values()));
            } else if (!fast.isEmpty()) {
                nativeValidationsLeft.decrementAndGet();
            }
        }
        return swept;
    }

    private Map<StatisticKey, Long> readNative(Player player) {
        try {
            return nativeReader.read(player, unsupportedKeys);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disableNative(e.toString());
            return null;
        }
    }

    private void disableNative(String reason) {
        if (!nativeDisabled) {
            nativeDisabled = true;
            LOGGER.warn("[STATS] Fast statistics read disabled, using full API sweep: {}", reason);
        }
    }

    private static String describeMismatch(Map<StatisticKey, Long> fast, Map<StatisticKey, Long> swept) {
        Set<StatisticKey> keys = new HashSet<>(fast.keySet());
        keys.addAll(swept.keySet());
        keys.removeIf(key -> java.util.Objects.equals(fast.get(key), swept.get(key)));
        return "(" + keys.size() + " keys differ, e.g. " + keys.stream().limit(5).toList() + ")";
    }

    private StatisticsSnapshot sweep(Player player) {
        Map<StatisticKey, Long> values = new HashMap<>();
        boolean pruned = false;

        StatisticsRegistry.StatisticDescriptor[] descriptors = this.activeDescriptors;
        for (int i = 0; i < descriptors.length; i++) {
            StatisticsRegistry.StatisticDescriptor desc = descriptors[i];
            try {
                int val = switch (desc.type()) {
                    case UNTYPED -> player.getStatistic(desc.statistic());
                    case BLOCK, ITEM -> player.getStatistic(desc.statistic(), desc.material());
                    case ENTITY -> player.getStatistic(desc.statistic(), desc.entityType());
                };

                if (val > 0) {
                    values.put(desc.key(), (long) val);
                }
            } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
                // Cache unsupported or illegal substatistic combinations on this version
                // so we don't repeatedly construct and throw exceptions on every capture tick.
                unsupportedKeys.add(desc.key());
                pruned = true;
            }
        }

        if (pruned) {
            pruneActiveDescriptors();
        }

        return new StatisticsSnapshot(values);
    }

    private synchronized void pruneActiveDescriptors() {
        activeDescriptors = java.util.Arrays.stream(activeDescriptors)
                .filter(desc -> !unsupportedKeys.contains(desc.key()))
                .toArray(StatisticsRegistry.StatisticDescriptor[]::new);
    }

    public boolean isKeyUnsupported(StatisticKey key) {
        return unsupportedKeys.contains(key);
    }
}
