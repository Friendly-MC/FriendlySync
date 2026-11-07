package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.model.pdc.PersistentDataKey;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataValue;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view of a player's synchronized PersistentDataContainer.
 */
public record PersistentDataView(PersistentDataSnapshot snapshot) {

    public PersistentDataView(PersistentDataSnapshot snapshot) {
        this.snapshot = snapshot != null ? snapshot : PersistentDataSnapshot.EMPTY;
    }

    public int size() {
        return snapshot.size();
    }

    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    public Set<PersistentDataKey> getKeys() {
        return snapshot.entries().keySet();
    }

    public boolean has(PersistentDataKey key) {
        return snapshot.containsKey(key);
    }

    public PersistentDataValue get(PersistentDataKey key) {
        return snapshot.get(key);
    }

    public Map<PersistentDataKey, PersistentDataValue> asMap() {
        return Collections.unmodifiableMap(snapshot.entries());
    }
}
