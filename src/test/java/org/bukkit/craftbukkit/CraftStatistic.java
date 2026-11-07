package org.bukkit.craftbukkit;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;

/** Test stand-in for CraftBukkit's reverse statistic mappers used by NativeStatisticsReader. */
public final class CraftStatistic {

    public record Stat(Statistic statistic, Material material, EntityType entityType) {}

    public static Statistic getBukkitStatistic(Stat stat) {
        return stat.statistic();
    }

    public static Material getMaterialFromStatistic(Stat stat) {
        return stat.material();
    }

    public static EntityType getEntityTypeFromStatistic(Stat stat) {
        return stat.entityType();
    }
}
