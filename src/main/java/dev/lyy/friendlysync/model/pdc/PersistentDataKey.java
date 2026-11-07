package dev.lyy.friendlysync.model.pdc;

import dev.lyy.friendlysync.util.Preconditions;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable key identifying a persistent data entry, preserving namespace and key components.
 */
public record PersistentDataKey(String namespace, String key) {

    private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-z0-9._-]+$");

    public PersistentDataKey {
        Preconditions.checkNotNull(namespace, "namespace cannot be null");
        Preconditions.checkNotNull(key, "key cannot be null");
        namespace = namespace.toLowerCase();
        key = key.toLowerCase();
        if (namespace.isEmpty() || !VALID_IDENTIFIER.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: '" + namespace + "'");
        }
        if (key.isEmpty() || !VALID_IDENTIFIER.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid key: '" + key + "'");
        }
    }

    public static PersistentDataKey of(String namespacedKey) {
        Preconditions.checkNotNull(namespacedKey, "namespacedKey cannot be null");
        int colon = namespacedKey.indexOf(':');
        if (colon == -1) {
            return new PersistentDataKey("minecraft", namespacedKey);
        }
        return new PersistentDataKey(namespacedKey.substring(0, colon), namespacedKey.substring(colon + 1));
    }

    public static PersistentDataKey of(org.bukkit.NamespacedKey namespacedKey) {
        Preconditions.checkNotNull(namespacedKey, "namespacedKey cannot be null");
        return new PersistentDataKey(namespacedKey.getNamespace(), namespacedKey.getKey());
    }

    public org.bukkit.NamespacedKey toNamespacedKey() {
        return new org.bukkit.NamespacedKey(namespace, key);
    }

    @Override
    public String toString() {
        return namespace + ":" + key;
    }
}
