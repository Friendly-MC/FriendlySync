package dev.lyy.friendlysync.sync.stats;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads only the non-zero entries of a player's server-side stats map instead of probing every
 * Statistic x Material x EntityType combination through the Bukkit API (thousands of registry lookups).
 * Uses CraftBukkit's own reverse mappers, so keys match the API sweep. Any failure throws and the caller
 * falls back to the sweep. Must execute on the player's owning thread.
 */
final class NativeStatisticsReader {

    private static final StatisticsRegistry.StatisticDescriptor UNMAPPED =
        new StatisticsRegistry.StatisticDescriptor(null, null, null, null, null, null);

    private record Accessors(Method getHandle, Method getStats, Field stats,
                             Method bukkitStatistic, Method material, Method entityType) {}

    private final StatisticsRegistry registry;
    private final Map<Object, StatisticsRegistry.StatisticDescriptor> descriptorsByStat = new ConcurrentHashMap<>();
    private volatile Accessors accessors;

    NativeStatisticsReader(StatisticsRegistry registry) {
        this.registry = registry;
    }

    Map<StatisticKey, Long> read(Player player, Set<StatisticKey> unsupportedKeys) throws ReflectiveOperationException {
        Accessors a = accessors;
        if (a == null) {
            accessors = a = resolve(player);
        }
        Object counter = a.getStats().invoke(a.getHandle().invoke(player));
        Map<?, ?> raw = (Map<?, ?>) a.stats().get(counter);

        List<Object> stats = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        // Vanilla wraps this map with Object2IntMaps.synchronize, which locks on the map itself.
        synchronized (raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                int value = ((Number) entry.getValue()).intValue();
                if (value > 0) {
                    stats.add(entry.getKey());
                    counts.add(value);
                }
            }
        }

        Map<StatisticKey, Long> values = new HashMap<>();
        for (int i = 0; i < stats.size(); i++) {
            StatisticsRegistry.StatisticDescriptor desc = descriptorsByStat.get(stats.get(i));
            if (desc == null) {
                desc = describe(a, stats.get(i));
                descriptorsByStat.put(stats.get(i), desc);
            }
            if (desc != UNMAPPED && !unsupportedKeys.contains(desc.key())) {
                values.put(desc.key(), (long) counts.get(i));
            }
        }
        return values;
    }

    private StatisticsRegistry.StatisticDescriptor describe(Accessors a, Object stat) throws ReflectiveOperationException {
        Statistic statistic = (Statistic) a.bukkitStatistic().invoke(null, stat);
        if (statistic == null) {
            return UNMAPPED;
        }
        StatisticsRegistry.StatisticDescriptor desc = switch (statistic.getType()) {
            case UNTYPED -> registry.find(statistic, null, null);
            case BLOCK, ITEM -> registry.find(statistic, (Material) a.material().invoke(null, stat), null);
            case ENTITY -> registry.find(statistic, null, (EntityType) a.entityType().invoke(null, stat));
        };
        return desc != null ? desc : UNMAPPED;
    }

    private static Accessors resolve(Player player) throws ReflectiveOperationException {
        Method getHandle = player.getClass().getMethod("getHandle");
        Object handle = getHandle.invoke(player);
        Method getStats = handle.getClass().getMethod("getStats");
        Field stats = statsField(getStats.invoke(handle).getClass());
        Class<?> craftStatistic = Class.forName("org.bukkit.craftbukkit.CraftStatistic", true, handle.getClass().getClassLoader());
        return new Accessors(getHandle, getStats, stats,
            staticMethod(craftStatistic, "getBukkitStatistic"),
            staticMethod(craftStatistic, "getMaterialFromStatistic"),
            staticMethod(craftStatistic, "getEntityTypeFromStatistic"));
    }

    private static Field statsField(Class<?> type) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getName().equals("stats") && Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        throw new NoSuchFieldException("stats map on " + type.getName());
    }

    private static Method staticMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1 && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
