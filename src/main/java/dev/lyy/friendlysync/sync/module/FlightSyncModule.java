package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.FlightSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.entity.Player;

/**
 * Synchronizes flight ability, flying state, fire ticks, remaining air, and fall distance.
 */
public final class FlightSyncModule implements SyncModule<FlightSnapshot> {

    @Override
    public String id() {
        return "flight";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public FlightSnapshot capture(Player player) {
        return new FlightSnapshot(
            player.getAllowFlight(),
            player.isFlying(),
            player.getFireTicks(),
            player.getRemainingAir(),
            player.getFallDistance()
        );
    }

    @Override
    public void apply(Player player, FlightSnapshot data) {
        if (data == null) {
            return;
        }

        player.setAllowFlight(data.allowFlight());
        if (data.allowFlight()) {
            player.setFlying(data.isFlying());
        }
        player.setFireTicks(Math.max(0, data.fireTicks()));
        player.setRemainingAir(data.remainingAir());
        player.setFallDistance(Math.max(0.0f, data.fallDistance()));
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("flight");
    }
}
