package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.LocationSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional module for synchronizing player location by world name.
 */
public final class LocationSyncModule implements SyncModule<LocationSnapshot> {

    // Session activation can precede completion of native asynchronous location restores.
    private final Map<UUID, Integer> pendingTeleports = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "location";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public LocationSnapshot capture(Player player) {
        Location loc = player.getLocation();
        return new LocationSnapshot(
            loc.getWorld() != null ? loc.getWorld().getName() : "world",
            loc.getX(),
            loc.getY(),
            loc.getZ(),
            loc.getYaw(),
            loc.getPitch()
        );
    }

    @Override
    public void apply(Player player, LocationSnapshot data) {
        if (data == null || data.world() == null) {
            return;
        }

        World world = Bukkit.getWorld(data.world());
        if (world == null) {
            // Keep the native join destination; foreign coordinates are unsafe in another dimension.
            return;
        }

        Location loc = new Location(world, data.x(), data.y(), data.z(), data.yaw(), data.pitch());
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> teleport = player.teleportAsync(loc);
        pendingTeleports.merge(uuid, 1, Integer::sum);
        teleport.whenComplete((success, error) -> pendingTeleports.computeIfPresent(uuid,
                (key, count) -> count == 1 ? null : count - 1));
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("location");
    }

    public boolean isTeleportPending(UUID uuid) {
        return pendingTeleports.containsKey(uuid);
    }
}
