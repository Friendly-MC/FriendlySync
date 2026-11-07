package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.ExperienceSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.entity.Player;

/**
 * Synchronizes player experience level, progress, and total experience.
 */
public final class ExperienceSyncModule implements SyncModule<ExperienceSnapshot> {

    @Override
    public String id() {
        return "experience";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public ExperienceSnapshot capture(Player player) {
        org.bukkit.World world = player.getWorld();
        boolean keepLevel = dev.lyy.friendlysync.listener.PlayerDeathListener.hasKeepLevel(player.getUniqueId(),
            world != null && Boolean.TRUE.equals(world.getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY)));
        if (player.isDead() && !keepLevel) {
            return new ExperienceSnapshot(0, 0.0f, 0);
        }

        return new ExperienceSnapshot(
            player.getLevel(),
            player.getExp(),
            player.getTotalExperience()
        );
    }

    @Override
    public void apply(Player player, ExperienceSnapshot data) {
        if (data == null) {
            return;
        }

        player.setLevel(Math.max(0, data.level()));
        player.setExp(Math.max(0.0f, Math.min(1.0f, data.expProgress())));
        player.setTotalExperience(Math.max(0, data.totalExperience()));
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("experience");
    }
}
