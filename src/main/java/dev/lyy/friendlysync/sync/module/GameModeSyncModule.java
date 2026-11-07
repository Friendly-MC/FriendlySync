package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.GameModeSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Synchronizes player GameMode.
 */
public final class GameModeSyncModule implements SyncModule<GameModeSnapshot> {

    @Override
    public String id() {
        return "game-mode";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public GameModeSnapshot capture(Player player) {
        return new GameModeSnapshot(player.getGameMode().name());
    }

    @Override
    public void apply(Player player, GameModeSnapshot data) {
        if (data == null || data.gameMode() == null) {
            return;
        }

        try {
            GameMode mode = GameMode.valueOf(data.gameMode());
            player.setGameMode(mode);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("game-mode");
    }
}
