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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for authoritatively applying a PersistentDataSnapshot to a player's PersistentDataContainer.
 * Removes stale managed keys and restores authoritative snapshot entries.
 * Must execute on the player's owning thread.
 */
public final class PersistentDataApplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentDataApplyService.class);

    private final PersistentDataPolicy policy;

    public PersistentDataApplyService(PersistentDataPolicy policy) {
        this.policy = Preconditions.checkNotNull(policy, "policy cannot be null");
    }

    public void apply(Player player, PersistentDataSnapshot snapshot) {
        Preconditions.checkNotNull(player, "player cannot be null");
        if (snapshot == null) {
            snapshot = PersistentDataSnapshot.EMPTY;
        }

        PersistentDataContainer container = player.getPersistentDataContainer();
        applyToContainer(container, snapshot, 0);
    }

    public void applyToContainer(PersistentDataContainer container, PersistentDataSnapshot snapshot, int depth) {
        if (container == null || depth > policy.getMaxDepth()) {
            return;
        }

        Set<NamespacedKey> snapshotKeys = new HashSet<>();
        for (PersistentDataKey pKey : snapshot.entries().keySet()) {
            snapshotKeys.add(pKey.toNamespacedKey());
        }

        // 2 & 3. Remove managed keys on the player that are no longer present in the authoritative snapshot
        Set<NamespacedKey> existingKeys = new HashSet<>(container.getKeys());
        for (NamespacedKey existingKey : existingKeys) {
            if (policy.isManaged(existingKey) && !snapshotKeys.contains(existingKey)) {
                container.remove(existingKey);
            }
        }

        for (Map.Entry<PersistentDataKey, PersistentDataValue> entry : snapshot.entries().entrySet()) {
            NamespacedKey key = entry.getKey().toNamespacedKey();
            if (!policy.isManaged(key)) {
                continue;
            }

            try {
                setValue(container, key, entry.getValue(), depth);
            } catch (Throwable t) {
                LOGGER.error("[PDC] Failed to apply key {}: {}", key, t.getMessage(), t);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void setValue(PersistentDataContainer container, NamespacedKey key, PersistentDataValue val, int depth) {
        switch (val) {
            case PersistentDataValue.ByteValue b -> container.set(key, PersistentDataType.BYTE, b.value());
            case PersistentDataValue.ShortValue s -> container.set(key, PersistentDataType.SHORT, s.value());
            case PersistentDataValue.IntegerValue i -> container.set(key, PersistentDataType.INTEGER, i.value());
            case PersistentDataValue.LongValue l -> container.set(key, PersistentDataType.LONG, l.value());
            case PersistentDataValue.FloatValue f -> container.set(key, PersistentDataType.FLOAT, f.value());
            case PersistentDataValue.DoubleValue d -> container.set(key, PersistentDataType.DOUBLE, d.value());
            case PersistentDataValue.StringValue str -> container.set(key, PersistentDataType.STRING, str.value());
            case PersistentDataValue.ByteArrayValue ba -> container.set(key, PersistentDataType.BYTE_ARRAY, ba.value());
            case PersistentDataValue.IntegerArrayValue ia -> container.set(key, PersistentDataType.INTEGER_ARRAY, ia.value());
            case PersistentDataValue.LongArrayValue la -> container.set(key, PersistentDataType.LONG_ARRAY, la.value());
            case PersistentDataValue.ContainerValue cv -> {
                if (depth < policy.getMaxDepth()) {
                    PersistentDataContainer nested = container.getAdapterContext().newPersistentDataContainer();
                    applyToContainer(nested, cv.snapshot(), depth + 1);
                    container.set(key, PersistentDataType.TAG_CONTAINER, nested);
                }
            }
            case PersistentDataValue.ContainerArrayValue cav -> {
                if (depth < policy.getMaxDepth()) {
                    List<PersistentDataSnapshot> snaps = cav.snapshots();
                    PersistentDataContainer[] array = new PersistentDataContainer[snaps.size()];
                    for (int i = 0; i < snaps.size(); i++) {
                        PersistentDataContainer child = container.getAdapterContext().newPersistentDataContainer();
                        applyToContainer(child, snaps.get(i), depth + 1);
                        array[i] = child;
                    }
                    container.set(key, PersistentDataType.TAG_CONTAINER_ARRAY, array);
                }
            }
        }
    }
}
