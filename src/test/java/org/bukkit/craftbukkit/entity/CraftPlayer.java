package org.bukkit.craftbukkit.entity;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Test stand-in for CraftPlayer -> ServerPlayer -> ServerStatsCounter. */
public abstract class CraftPlayer implements Player {

    public abstract Handle getHandle();

    public static class Handle {
        private final StatsCounter stats = new StatsCounter();

        public Handle(Map<Object, Integer> values) {
            stats.stats.putAll(values);
        }

        public StatsCounter getStats() {
            return stats;
        }
    }

    public static class StatsCounter {
        protected final Map<Object, Integer> stats = Collections.synchronizedMap(new HashMap<>());
    }
}
