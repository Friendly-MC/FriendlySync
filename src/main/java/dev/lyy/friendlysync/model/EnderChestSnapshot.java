package dev.lyy.friendlysync.model;

import java.util.Arrays;

/**
 * Immutable snapshot of player ender chest items.
 */
public record EnderChestSnapshot(
    byte[][] items
) {
    public EnderChestSnapshot {
        items = items != null ? cloneArray(items) : new byte[27][];
    }

    private static byte[][] cloneArray(byte[][] array) {
        byte[][] result = new byte[array.length][];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i] != null ? array[i].clone() : null;
        }
        return result;
    }

    @Override
    public byte[][] items() {
        return cloneArray(items);
    }

    public byte[][] itemsRaw() {
        return items;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnderChestSnapshot that)) return false;
        return Arrays.deepEquals(items, that.items);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(items);
    }
}
