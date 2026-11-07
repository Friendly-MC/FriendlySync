package dev.lyy.friendlysync.sync.pdc;

import org.bukkit.NamespacedKey;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Policy defining which PersistentDataContainer namespaces are synchronized and enforcing size bounds.
 */
public final class PersistentDataPolicy {

    public enum Mode {
        ALL_SAFE_KEYS,
        MANAGED_NAMESPACES
    }

    private final boolean enabled;
    private final Mode mode;
    private final Set<String> managedNamespaces;
    private final Set<String> excludedNamespaces;
    private final int maxTotalBytes;
    private final int maxEntryBytes;
    private final int maxDepth;

    public PersistentDataPolicy(
        boolean enabled,
        String modeStr,
        List<String> managedList,
        List<String> excludedList,
        int maxTotalBytes,
        int maxEntryBytes,
        int maxDepth
    ) {
        this.enabled = enabled;
        this.mode = "MANAGED_NAMESPACES".equalsIgnoreCase(modeStr) ? Mode.MANAGED_NAMESPACES : Mode.ALL_SAFE_KEYS;

        Set<String> managed = new HashSet<>();
        if (managedList != null) {
            for (String s : managedList) {
                if (s != null && !s.isBlank()) managed.add(s.trim().toLowerCase());
            }
        }
        this.managedNamespaces = Collections.unmodifiableSet(managed);

        Set<String> excluded = new HashSet<>();
        excluded.add("friendlysync"); // reserved internal namespace always excluded
        if (excludedList != null) {
            for (String s : excludedList) {
                if (s != null && !s.isBlank()) excluded.add(s.trim().toLowerCase());
            }
        }
        this.excludedNamespaces = Collections.unmodifiableSet(excluded);

        this.maxTotalBytes = maxTotalBytes > 0 ? maxTotalBytes : 1_048_576;
        this.maxEntryBytes = maxEntryBytes > 0 ? maxEntryBytes : 262_144;
        this.maxDepth = maxDepth > 0 ? maxDepth : 16;
    }

    public static PersistentDataPolicy defaultPolicy() {
        return new PersistentDataPolicy(true, "ALL_SAFE_KEYS", List.of(), List.of("friendlysync"), 1_048_576, 262_144, 16);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public int getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public int getMaxEntryBytes() {
        return maxEntryBytes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public boolean isManaged(NamespacedKey key) {
        if (key == null) return false;
        String ns = key.getNamespace().toLowerCase();
        if (excludedNamespaces.contains(ns)) {
            return false;
        }
        if (mode == Mode.MANAGED_NAMESPACES) {
            return managedNamespaces.contains(ns);
        }
        return true;
    }
}
