package dev.lyy.friendlysync.sync.pdc;

import dev.lyy.friendlysync.model.pdc.PersistentDataKey;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataValue;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for safely capturing an immutable PersistentDataSnapshot from a player's PersistentDataContainer.
 * Must execute on the player's owning thread.
 */
public final class PersistentDataCaptureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentDataCaptureService.class);

    private final PersistentDataPolicy policy;

    public PersistentDataCaptureService(PersistentDataPolicy policy) {
        this.policy = Preconditions.checkNotNull(policy, "policy cannot be null");
    }

    public PersistentDataSnapshot capture(Player player) {
        Preconditions.checkNotNull(player, "player cannot be null");
        PersistentDataContainer container = player.getPersistentDataContainer();
        return captureContainer(container, 0);
    }

    public PersistentDataSnapshot captureContainer(PersistentDataContainer container, int depth) {
        if (container == null || depth > policy.getMaxDepth()) {
            return PersistentDataSnapshot.EMPTY;
        }

        Set<NamespacedKey> keys = container.getKeys();
        if (keys.isEmpty()) {
            return PersistentDataSnapshot.EMPTY;
        }

        Map<PersistentDataKey, PersistentDataValue> map = new HashMap<>(keys.size());
        long totalBytes = 0;

        for (NamespacedKey key : keys) {
            if (!policy.isManaged(key)) {
                continue;
            }

            PersistentDataValue value = readValue(container, key, depth);
            if (value != null) {
                PersistentDataKey pKey = new PersistentDataKey(key.getNamespace(), key.getKey());
                map.put(pKey, value);

                totalBytes += estimateValueBytes(value);
                if (totalBytes > policy.getMaxTotalBytes()) {
                    LOGGER.warn("[PDC] Exceeded maximum allowed PDC total bytes ({} > {}). Truncating remaining keys.",
                        totalBytes, policy.getMaxTotalBytes());
                    break;
                }
            }
        }

        return new PersistentDataSnapshot(map);
    }

    @SuppressWarnings("deprecation")
    private PersistentDataValue readValue(PersistentDataContainer container, NamespacedKey key, int depth) {
        try {
            if (depth < policy.getMaxDepth() && container.has(key, PersistentDataType.TAG_CONTAINER)) {
                PersistentDataContainer nested = container.get(key, PersistentDataType.TAG_CONTAINER);
                if (nested != null) {
                    return new PersistentDataValue.ContainerValue(captureContainer(nested, depth + 1));
                }
            }

            if (depth < policy.getMaxDepth() && container.has(key, PersistentDataType.TAG_CONTAINER_ARRAY)) {
                PersistentDataContainer[] array = container.get(key, PersistentDataType.TAG_CONTAINER_ARRAY);
                if (array != null) {
                    List<PersistentDataSnapshot> snapshots = new ArrayList<>(array.length);
                    for (PersistentDataContainer child : array) {
                        snapshots.add(captureContainer(child, depth + 1));
                    }
                    return new PersistentDataValue.ContainerArrayValue(snapshots);
                }
            }

            if (container.has(key, PersistentDataType.STRING)) {
                String str = container.get(key, PersistentDataType.STRING);
                if (str != null) {
                    if (str.getBytes(StandardCharsets.UTF_8).length > policy.getMaxEntryBytes()) {
                        LOGGER.warn("[PDC] Skipping key {} exceeding max entry bytes", key);
                        return null;
                    }
                    return new PersistentDataValue.StringValue(str);
                }
            }

            if (container.has(key, PersistentDataType.INTEGER)) {
                Integer i = container.get(key, PersistentDataType.INTEGER);
                if (i != null) return new PersistentDataValue.IntegerValue(i);
            }

            if (container.has(key, PersistentDataType.LONG)) {
                Long l = container.get(key, PersistentDataType.LONG);
                if (l != null) return new PersistentDataValue.LongValue(l);
            }

            if (container.has(key, PersistentDataType.DOUBLE)) {
                Double d = container.get(key, PersistentDataType.DOUBLE);
                if (d != null) return new PersistentDataValue.DoubleValue(d);
            }

            if (container.has(key, PersistentDataType.FLOAT)) {
                Float f = container.get(key, PersistentDataType.FLOAT);
                if (f != null) return new PersistentDataValue.FloatValue(f);
            }

            if (container.has(key, PersistentDataType.SHORT)) {
                Short s = container.get(key, PersistentDataType.SHORT);
                if (s != null) return new PersistentDataValue.ShortValue(s);
            }

            if (container.has(key, PersistentDataType.BYTE)) {
                Byte b = container.get(key, PersistentDataType.BYTE);
                if (b != null) return new PersistentDataValue.ByteValue(b);
            }

            if (container.has(key, PersistentDataType.BYTE_ARRAY)) {
                byte[] ba = container.get(key, PersistentDataType.BYTE_ARRAY);
                if (ba != null) {
                    if (ba.length > policy.getMaxEntryBytes()) {
                        LOGGER.warn("[PDC] Skipping byte array key {} exceeding max entry bytes", key);
                        return null;
                    }
                    return new PersistentDataValue.ByteArrayValue(ba);
                }
            }

            if (container.has(key, PersistentDataType.INTEGER_ARRAY)) {
                int[] ia = container.get(key, PersistentDataType.INTEGER_ARRAY);
                if (ia != null) {
                    if (ia.length * 4 > policy.getMaxEntryBytes()) {
                        LOGGER.warn("[PDC] Skipping int array key {} exceeding max entry bytes", key);
                        return null;
                    }
                    return new PersistentDataValue.IntegerArrayValue(ia);
                }
            }

            if (container.has(key, PersistentDataType.LONG_ARRAY)) {
                long[] la = container.get(key, PersistentDataType.LONG_ARRAY);
                if (la != null) {
                    if (la.length * 8 > policy.getMaxEntryBytes()) {
                        LOGGER.warn("[PDC] Skipping long array key {} exceeding max entry bytes", key);
                        return null;
                    }
                    return new PersistentDataValue.LongArrayValue(la);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[PDC] Failed to inspect key {}: {}", key, t.getMessage());
        }

        return null;
    }

    private static long estimateValueBytes(PersistentDataValue val) {
        if (val instanceof PersistentDataValue.ByteValue) return 1;
        if (val instanceof PersistentDataValue.ShortValue) return 2;
        if (val instanceof PersistentDataValue.IntegerValue) return 4;
        if (val instanceof PersistentDataValue.LongValue) return 8;
        if (val instanceof PersistentDataValue.FloatValue) return 4;
        if (val instanceof PersistentDataValue.DoubleValue) return 8;
        if (val instanceof PersistentDataValue.StringValue s) return s.value().length();
        if (val instanceof PersistentDataValue.ByteArrayValue b) return b.value().length;
        if (val instanceof PersistentDataValue.IntegerArrayValue ia) return (long) ia.value().length * 4;
        if (val instanceof PersistentDataValue.LongArrayValue la) return (long) la.value().length * 8;
        return 64;
    }
}
