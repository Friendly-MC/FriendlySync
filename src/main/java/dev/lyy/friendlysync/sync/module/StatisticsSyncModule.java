package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import dev.lyy.friendlysync.sync.stats.StatisticsApplyService;
import dev.lyy.friendlysync.sync.stats.StatisticsCaptureService;
import dev.lyy.friendlysync.sync.stats.StatisticsRegistry;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;

/**
 * Synchronization module for player statistics.
 */
public final class StatisticsSyncModule implements SyncModule<StatisticsSnapshot> {

    private final StatisticsRegistry registry;
    private final StatisticsCaptureService captureService;
    private final StatisticsApplyService applyService;

    public StatisticsSyncModule(StatisticsRegistry registry) {
        this.registry = Preconditions.checkNotNull(registry, "registry cannot be null");
        this.captureService = new StatisticsCaptureService(registry);
        this.applyService = new StatisticsApplyService(registry, captureService);
    }

    @Override
    public String id() {
        return "statistics";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public StatisticsSnapshot capture(Player player) {
        return captureService.capture(player);
    }

    @Override
    public void apply(Player player, StatisticsSnapshot data) {
        applyService.apply(player, data);
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isStatisticsEnabled();
    }

    public StatisticsRegistry registry() {
        return registry;
    }
}
