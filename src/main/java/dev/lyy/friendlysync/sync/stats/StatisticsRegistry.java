package dev.lyy.friendlysync.sync.stats;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticQualifierType;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputed registry of valid player statistics descriptors for the active Minecraft server version.
 * Prevents expensive runtime combinatorial enumeration.
 */
public final class StatisticsRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsRegistry.class);

    public record StatisticDescriptor(
        Statistic statistic,
        Statistic.Type type,
        StatisticQualifierType qualifierType,
        StatisticKey key,
        Material material,
        EntityType entityType
    ) {}

    private final List<StatisticDescriptor> descriptors;
    private final Map<StatisticKey, StatisticDescriptor> descriptorsByKey;

    public StatisticsRegistry() {
        List<StatisticDescriptor> list = new ArrayList<>();
        Map<StatisticKey, StatisticDescriptor> map = new HashMap<>();

        for (Statistic stat : Statistic.values()) {
            Statistic.Type type;
            try {
                type = stat.getType();
            } catch (Throwable ignored) {
                continue;
            }
            String statName = statName(stat);

            switch (type) {
                case UNTYPED -> {
                    StatisticKey key = StatisticKey.untyped(statName);
                    StatisticDescriptor desc = new StatisticDescriptor(stat, type, StatisticQualifierType.NONE, key, null, null);
                    list.add(desc);
                    map.put(key, desc);
                }
                case BLOCK -> {
                    for (Material mat : Material.values()) {
                        try {
                            if (isBlock(mat) && !isLegacy(mat)) {
                                if (stat == Statistic.MINE_BLOCK && isFluidOrAir(mat)) {
                                    continue;
                                }
                                String matKey = getMaterialKey(mat);
                                StatisticKey key = StatisticKey.material(statName, matKey);
                                StatisticDescriptor desc = new StatisticDescriptor(stat, type, StatisticQualifierType.MATERIAL, key, mat, null);
                                list.add(desc);
                                map.put(key, desc);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                case ITEM -> {
                    for (Material mat : Material.values()) {
                        try {
                            if (isItem(mat) && !isLegacy(mat)) {
                                if (stat == Statistic.BREAK_ITEM && !isBreakable(mat)) {
                                    continue;
                                }
                                String matKey = getMaterialKey(mat);
                                StatisticKey key = StatisticKey.material(statName, matKey);
                                StatisticDescriptor desc = new StatisticDescriptor(stat, type, StatisticQualifierType.MATERIAL, key, mat, null);
                                list.add(desc);
                                map.put(key, desc);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                case ENTITY -> {
                    for (EntityType et : EntityType.values()) {
                        try {
                            if (et.isAlive() && et != EntityType.UNKNOWN && et != EntityType.PLAYER) {
                                String entityKey = getEntityKey(et);
                                StatisticKey key = StatisticKey.entity(statName, entityKey);
                                StatisticDescriptor desc = new StatisticDescriptor(stat, type, StatisticQualifierType.ENTITY, key, null, et);
                                list.add(desc);
                                map.put(key, desc);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        this.descriptors = Collections.unmodifiableList(list);
        this.descriptorsByKey = Collections.unmodifiableMap(map);
        LOGGER.info("[STATS] Precomputed {} valid statistic combinations for current server version", descriptors.size());
    }

    public List<StatisticDescriptor> allDescriptors() {
        return descriptors;
    }

    public StatisticDescriptor findByKey(StatisticKey key) {
        return descriptorsByKey.get(key);
    }

    /**
     * Finds the descriptor for a Bukkit statistic and its qualifier, or null when it is not synchronized.
     */
    public StatisticDescriptor find(Statistic stat, Material material, EntityType entityType) {
        String name = statName(stat);
        StatisticKey key = switch (stat.getType()) {
            case UNTYPED -> StatisticKey.untyped(name);
            case BLOCK, ITEM -> material == null ? null : StatisticKey.material(name, getMaterialKey(material));
            case ENTITY -> entityType == null ? null : StatisticKey.entity(name, getEntityKey(entityType));
        };
        return key == null ? null : descriptorsByKey.get(key);
    }

    private static String statName(Statistic stat) {
        return "minecraft:" + stat.name().toLowerCase();
    }

    private static boolean isItem(Material mat) {
        try {
            return mat.isItem() && !isAir(mat);
        } catch (Throwable ignored) {
            try {
                String name = mat.name();
                return !isLegacy(mat) && !name.contains("AIR");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static boolean isBlock(Material mat) {
        try {
            return mat.isBlock() && !isAir(mat);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAir(Material mat) {
        try {
            return mat.isAir();
        } catch (Throwable ignored) {
            try {
                return mat.name().contains("AIR");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static boolean isFluidOrAir(Material mat) {
        return isAir(mat) || mat == Material.WATER || mat == Material.LAVA || mat == Material.BUBBLE_COLUMN;
    }

    private static boolean isLegacy(Material mat) {
        try {
            return mat.isLegacy();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBreakable(Material mat) {
        try {
            return mat.getMaxDurability() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String getMaterialKey(Material mat) {
        try {
            return mat.getKey().toString();
        } catch (Throwable ignored) {
            return "minecraft:" + mat.name().toLowerCase();
        }
    }

    private static String getEntityKey(EntityType et) {
        try {
            return et.getKey().toString();
        } catch (Throwable ignored) {
            return "minecraft:" + et.name().toLowerCase();
        }
    }
}
