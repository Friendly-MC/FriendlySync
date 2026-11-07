package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.HungerSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.entity.Player;

/**
 * Synchronizes player food level, saturation, and exhaustion.
 */
public final class HungerSyncModule implements SyncModule<HungerSnapshot> {

    @Override
    public String id() {
        return "hunger";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public HungerSnapshot capture(Player player) {
        if (player.isDead()) {
            return new HungerSnapshot(20, 5.0f, 0.0f);
        }

        return new HungerSnapshot(
            player.getFoodLevel(),
            player.getSaturation(),
            player.getExhaustion()
        );
    }

    @Override
    public void apply(Player player, HungerSnapshot data) {
        if (data == null) {
            return;
        }

        player.setFoodLevel(Math.max(0, Math.min(20, data.foodLevel())));
        player.setSaturation(Math.max(0.0f, data.saturation()));
        player.setExhaustion(Math.max(0.0f, data.exhaustion()));
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("hunger");
    }
}
