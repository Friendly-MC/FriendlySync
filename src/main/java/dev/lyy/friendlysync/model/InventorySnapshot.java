package dev.lyy.friendlysync.model;

import java.util.Arrays;

/**
 * Immutable snapshot of player inventory item data.
 * All items are stored as independent serialized byte arrays to prevent thread safety issues.
 */
public record InventorySnapshot(
    byte[][] storage,
    byte[][] armor,
    byte[] offHand,
    int heldSlot,
    byte[] cursor
) {
    public InventorySnapshot {
        storage = storage != null ? cloneArray(storage) : new byte[36][];
        armor = armor != null ? cloneArray(armor) : new byte[4][];
        offHand = offHand != null ? offHand.clone() : null;
        cursor = cursor != null ? cursor.clone() : null;
    }

    private static byte[][] cloneArray(byte[][] array) {
        byte[][] result = new byte[array.length][];
        for (int i = 0; i < array.length; i++) {
            result[i] = array[i] != null ? array[i].clone() : null;
        }
        return result;
    }

    @Override
    public byte[][] storage() {
        return cloneArray(storage);
    }

    public byte[][] storageRaw() {
        return storage;
    }

    @Override
    public byte[][] armor() {
        return cloneArray(armor);
    }

    public byte[][] armorRaw() {
        return armor;
    }

    @Override
    public byte[] offHand() {
        return offHand != null ? offHand.clone() : null;
    }

    public byte[] offHandRaw() {
        return offHand;
    }

    @Override
    public byte[] cursor() {
        return cursor != null ? cursor.clone() : null;
    }

    public byte[] cursorRaw() {
        return cursor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventorySnapshot that)) return false;
        return heldSlot == that.heldSlot &&
            Arrays.deepEquals(storage, that.storage) &&
            Arrays.deepEquals(armor, that.armor) &&
            Arrays.equals(offHand, that.offHand) &&
            Arrays.equals(cursor, that.cursor);
    }

    @Override
    public int hashCode() {
        int result = Arrays.deepHashCode(storage);
        result = 31 * result + Arrays.deepHashCode(armor);
        result = 31 * result + Arrays.hashCode(offHand);
        result = 31 * result + heldSlot;
        result = 31 * result + Arrays.hashCode(cursor);
        return result;
    }
}
