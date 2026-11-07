package dev.lyy.friendlysync.model.pdc;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable snapshot of a player's PersistentDataContainer.
 */
public record PersistentDataSnapshot(Map<PersistentDataKey, PersistentDataValue> entries) {

    public static final PersistentDataSnapshot EMPTY = new PersistentDataSnapshot(Collections.emptyMap());

    public PersistentDataSnapshot {
        entries = entries != null ? Map.copyOf(entries) : Collections.emptyMap();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public boolean containsKey(PersistentDataKey key) {
        return entries.containsKey(key);
    }

    public PersistentDataValue get(PersistentDataKey key) {
        return entries.get(key);
    }

    public long estimatedByteSize() {
        long bytes = 0;
        for (Map.Entry<PersistentDataKey, PersistentDataValue> entry : entries.entrySet()) {
            bytes += entry.getKey().namespace().length() + entry.getKey().key().length() + 8;
            PersistentDataValue val = entry.getValue();
            if (val instanceof PersistentDataValue.ByteValue) bytes += 1;
            else if (val instanceof PersistentDataValue.ShortValue) bytes += 2;
            else if (val instanceof PersistentDataValue.IntegerValue) bytes += 4;
            else if (val instanceof PersistentDataValue.LongValue) bytes += 8;
            else if (val instanceof PersistentDataValue.FloatValue) bytes += 4;
            else if (val instanceof PersistentDataValue.DoubleValue) bytes += 8;
            else if (val instanceof PersistentDataValue.StringValue s) bytes += s.value().length();
            else if (val instanceof PersistentDataValue.ByteArrayValue b) bytes += b.value().length;
            else if (val instanceof PersistentDataValue.IntegerArrayValue ia) bytes += (long) ia.value().length * 4;
            else if (val instanceof PersistentDataValue.LongArrayValue la) bytes += (long) la.value().length * 8;
            else if (val instanceof PersistentDataValue.ContainerValue cv) bytes += cv.snapshot().estimatedByteSize();
            else if (val instanceof PersistentDataValue.ContainerArrayValue cav) {
                for (PersistentDataSnapshot snap : cav.snapshots()) {
                    bytes += snap.estimatedByteSize();
                }
            }
        }
        return bytes;
    }
}
