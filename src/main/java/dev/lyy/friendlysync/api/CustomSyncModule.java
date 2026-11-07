package dev.lyy.friendlysync.api;

import org.bukkit.entity.Player;

/**
 * Public API contract allowing external plugins to register custom synchronized player data modules.
 *
 * @param <T> module data type
 */
public interface CustomSyncModule<T> {

    String id();

    int version();

    T capture(Player player);

    void apply(Player player, T data);

    byte[] serialize(T data);

    T deserialize(byte[] data, int version);
}
