package dev.lyy.friendlysync.model.pdc;

import dev.lyy.friendlysync.util.Preconditions;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable sealed hierarchy representing all supported PersistentDataContainer value types.
 */
public sealed interface PersistentDataValue permits
    PersistentDataValue.ByteValue,
    PersistentDataValue.ShortValue,
    PersistentDataValue.IntegerValue,
    PersistentDataValue.LongValue,
    PersistentDataValue.FloatValue,
    PersistentDataValue.DoubleValue,
    PersistentDataValue.StringValue,
    PersistentDataValue.ByteArrayValue,
    PersistentDataValue.IntegerArrayValue,
    PersistentDataValue.LongArrayValue,
    PersistentDataValue.ContainerValue,
    PersistentDataValue.ContainerArrayValue {

    byte typeId();

    record ByteValue(byte value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 1;
        }
    }

    record ShortValue(short value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 2;
        }
    }

    record IntegerValue(int value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 3;
        }
    }

    record LongValue(long value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 4;
        }
    }

    record FloatValue(float value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 5;
        }
    }

    record DoubleValue(double value) implements PersistentDataValue {
        @Override
        public byte typeId() {
            return 6;
        }
    }

    record StringValue(String value) implements PersistentDataValue {
        public StringValue {
            Preconditions.checkNotNull(value, "value cannot be null");
        }

        @Override
        public byte typeId() {
            return 7;
        }
    }

    record ByteArrayValue(byte[] value) implements PersistentDataValue {
        public ByteArrayValue(byte[] value) {
            Preconditions.checkNotNull(value, "value cannot be null");
            this.value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public byte typeId() {
            return 8;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayValue that)) return false;
            return Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    record IntegerArrayValue(int[] value) implements PersistentDataValue {
        public IntegerArrayValue(int[] value) {
            Preconditions.checkNotNull(value, "value cannot be null");
            this.value = value.clone();
        }

        @Override
        public int[] value() {
            return value.clone();
        }

        @Override
        public byte typeId() {
            return 9;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IntegerArrayValue that)) return false;
            return Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    record LongArrayValue(long[] value) implements PersistentDataValue {
        public LongArrayValue(long[] value) {
            Preconditions.checkNotNull(value, "value cannot be null");
            this.value = value.clone();
        }

        @Override
        public long[] value() {
            return value.clone();
        }

        @Override
        public byte typeId() {
            return 10;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LongArrayValue that)) return false;
            return Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    record ContainerValue(PersistentDataSnapshot snapshot) implements PersistentDataValue {
        public ContainerValue {
            Preconditions.checkNotNull(snapshot, "snapshot cannot be null");
        }

        @Override
        public byte typeId() {
            return 11;
        }
    }

    record ContainerArrayValue(List<PersistentDataSnapshot> snapshots) implements PersistentDataValue {
        public ContainerArrayValue(List<PersistentDataSnapshot> snapshots) {
            Preconditions.checkNotNull(snapshots, "snapshots cannot be null");
            this.snapshots = List.copyOf(snapshots);
        }

        @Override
        public byte typeId() {
            return 12;
        }
    }
}
