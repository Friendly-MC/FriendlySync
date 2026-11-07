package dev.lyy.friendlysync.sync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import org.bukkit.entity.Player;

/**
 * Modular synchronization contract for a specific component of player state.
 *
 * @param <T> immutable domain snapshot representation
 */
public interface SyncModule<T> {

    /**
     * Unique identifier for this module (e.g. "inventory", "vitals").
     */
    String id();

    /**
     * Schema version for this module's payload structure.
     */
    int version();

    /**
     * Captures an immutable snapshot of this component.
     * MUST execute on the player's owning thread / entity scheduler.
     */
    T capture(Player player);

    /**
     * Applies snapshot state to the live player.
     * MUST execute on the player's owning thread / entity scheduler.
     */
    void apply(Player player, T data);

    /**
     * Determines whether this module is active based on configuration.
     */
    boolean isEnabled(FriendlySyncConfig config);
}
