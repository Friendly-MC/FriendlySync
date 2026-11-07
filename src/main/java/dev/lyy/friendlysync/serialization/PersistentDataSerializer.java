package dev.lyy.friendlysync.serialization;

import dev.lyy.friendlysync.model.pdc.PersistentDataKey;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataValue;
import dev.lyy.friendlysync.util.Preconditions;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary serializer for PersistentDataSnapshot enforcing bounded recursion depth and array sizes.
 */
public final class PersistentDataSerializer {

    public static final int MAX_ENTRIES = 10_000;
    public static final int MAX_DEPTH = 16;
    public static final int MAX_ARRAY_LENGTH = 100_000;
    public static final int MAX_STRING_BYTES = 262_144; // 256 KB

    private PersistentDataSerializer() {
    }

    public static void writeSnapshot(DataOutputStream out, PersistentDataSnapshot snapshot) throws IOException {
        writeSnapshot(out, snapshot, 0);
    }

    private static void writeSnapshot(DataOutputStream out, PersistentDataSnapshot snapshot, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new SerializationException("PersistentData recursion depth limit exceeded: " + depth);
        }
        if (snapshot == null || snapshot.isEmpty()) {
            out.writeInt(0);
            return;
        }

        Map<PersistentDataKey, PersistentDataValue> entries = snapshot.entries();
        out.writeInt(entries.size());

        for (Map.Entry<PersistentDataKey, PersistentDataValue> entry : entries.entrySet()) {
            PersistentDataKey key = entry.getKey();
            PersistentDataValue value = entry.getValue();

            out.writeUTF(key.namespace());
            out.writeUTF(key.key());
            out.writeByte(value.typeId());

            switch (value) {
                case PersistentDataValue.ByteValue b -> out.writeByte(b.value());
                case PersistentDataValue.ShortValue s -> out.writeShort(s.value());
                case PersistentDataValue.IntegerValue i -> out.writeInt(i.value());
                case PersistentDataValue.LongValue l -> out.writeLong(l.value());
                case PersistentDataValue.FloatValue f -> out.writeFloat(f.value());
                case PersistentDataValue.DoubleValue d -> out.writeDouble(d.value());
                case PersistentDataValue.StringValue str -> {
                    byte[] bytes = str.value().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_STRING_BYTES) {
                        throw new SerializationException("PDC string exceeds maximum bytes: " + bytes.length);
                    }
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
                case PersistentDataValue.ByteArrayValue ba -> {
                    byte[] bytes = ba.value();
                    if (bytes.length > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("PDC byte array exceeds maximum length: " + bytes.length);
                    }
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
                case PersistentDataValue.IntegerArrayValue ia -> {
                    int[] ints = ia.value();
                    if (ints.length > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("PDC int array exceeds maximum length: " + ints.length);
                    }
                    out.writeInt(ints.length);
                    for (int val : ints) {
                        out.writeInt(val);
                    }
                }
                case PersistentDataValue.LongArrayValue la -> {
                    long[] longs = la.value();
                    if (longs.length > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("PDC long array exceeds maximum length: " + longs.length);
                    }
                    out.writeInt(longs.length);
                    for (long val : longs) {
                        out.writeLong(val);
                    }
                }
                case PersistentDataValue.ContainerValue cv -> writeSnapshot(out, cv.snapshot(), depth + 1);
                case PersistentDataValue.ContainerArrayValue cav -> {
                    List<PersistentDataSnapshot> list = cav.snapshots();
                    out.writeInt(list.size());
                    for (PersistentDataSnapshot item : list) {
                        writeSnapshot(out, item, depth + 1);
                    }
                }
            }
        }
    }

    public static PersistentDataSnapshot readSnapshot(DataInputStream in) throws IOException {
        return readSnapshot(in, 0);
    }

    private static PersistentDataSnapshot readSnapshot(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new SerializationException("PersistentData recursion depth limit exceeded: " + depth);
        }

        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new SerializationException("Invalid PDC entry count: " + count);
        }
        if (count == 0) {
            return PersistentDataSnapshot.EMPTY;
        }

        Map<PersistentDataKey, PersistentDataValue> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            String namespace = in.readUTF();
            String key = in.readUTF();
            PersistentDataKey pKey = new PersistentDataKey(namespace, key);
            byte typeId = in.readByte();

            PersistentDataValue pVal = switch (typeId) {
                case 1 -> new PersistentDataValue.ByteValue(in.readByte());
                case 2 -> new PersistentDataValue.ShortValue(in.readShort());
                case 3 -> new PersistentDataValue.IntegerValue(in.readInt());
                case 4 -> new PersistentDataValue.LongValue(in.readLong());
                case 5 -> new PersistentDataValue.FloatValue(in.readFloat());
                case 6 -> new PersistentDataValue.DoubleValue(in.readDouble());
                case 7 -> {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_STRING_BYTES) {
                        throw new SerializationException("Invalid PDC string length: " + len);
                    }
                    byte[] bytes = new byte[len];
                    in.readFully(bytes);
                    yield new PersistentDataValue.StringValue(new String(bytes, StandardCharsets.UTF_8));
                }
                case 8 -> {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("Invalid PDC byte array length: " + len);
                    }
                    byte[] bytes = new byte[len];
                    in.readFully(bytes);
                    yield new PersistentDataValue.ByteArrayValue(bytes);
                }
                case 9 -> {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("Invalid PDC int array length: " + len);
                    }
                    int[] ints = new int[len];
                    for (int j = 0; j < len; j++) {
                        ints[j] = in.readInt();
                    }
                    yield new PersistentDataValue.IntegerArrayValue(ints);
                }
                case 10 -> {
                    int len = in.readInt();
                    if (len < 0 || len > MAX_ARRAY_LENGTH) {
                        throw new SerializationException("Invalid PDC long array length: " + len);
                    }
                    long[] longs = new long[len];
                    for (int j = 0; j < len; j++) {
                        longs[j] = in.readLong();
                    }
                    yield new PersistentDataValue.LongArrayValue(longs);
                }
                case 11 -> new PersistentDataValue.ContainerValue(readSnapshot(in, depth + 1));
                case 12 -> {
                    int arrLen = in.readInt();
                    if (arrLen < 0 || arrLen > 1000) {
                        throw new SerializationException("Invalid PDC container array length: " + arrLen);
                    }
                    List<PersistentDataSnapshot> list = new ArrayList<>(arrLen);
                    for (int j = 0; j < arrLen; j++) {
                        list.add(readSnapshot(in, depth + 1));
                    }
                    yield new PersistentDataValue.ContainerArrayValue(list);
                }
                default -> throw new SerializationException("Unknown PersistentDataType id: " + typeId);
            };

            map.put(pKey, pVal);
        }

        return new PersistentDataSnapshot(map);
    }
}
