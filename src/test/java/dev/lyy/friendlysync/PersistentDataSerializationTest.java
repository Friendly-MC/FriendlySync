package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.pdc.PersistentDataKey;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataValue;
import dev.lyy.friendlysync.serialization.PersistentDataSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersistentDataSerializationTest {

    @Test
    @DisplayName("PDC: Empty snapshot serializes and deserializes as EMPTY")
    void testEmptySnapshot() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        PersistentDataSerializer.writeSnapshot(dos, PersistentDataSnapshot.EMPTY);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        PersistentDataSnapshot restored = PersistentDataSerializer.readSnapshot(dis);

        assertTrue(restored.isEmpty());
        assertEquals(0, restored.size());
    }

    @Test
    @DisplayName("PDC: All primitive and complex types round-trip with exact fidelity")
    void testAllValueTypesRoundTrip() throws Exception {
        Map<PersistentDataKey, PersistentDataValue> map = new HashMap<>();
        map.put(new PersistentDataKey("test", "byte_key"), new PersistentDataValue.ByteValue((byte) 42));
        map.put(new PersistentDataKey("test", "short_key"), new PersistentDataValue.ShortValue((short) 1337));
        map.put(new PersistentDataKey("test", "int_key"), new PersistentDataValue.IntegerValue(100_000));
        map.put(new PersistentDataKey("test", "long_key"), new PersistentDataValue.LongValue(9876543210L));
        map.put(new PersistentDataKey("test", "float_key"), new PersistentDataValue.FloatValue(3.1415f));
        map.put(new PersistentDataKey("test", "double_key"), new PersistentDataValue.DoubleValue(2.718281828));
        map.put(new PersistentDataKey("test", "str_key"), new PersistentDataValue.StringValue("hello friendlysync"));
        map.put(new PersistentDataKey("test", "byte_arr"), new PersistentDataValue.ByteArrayValue(new byte[]{1, 2, 3, 4}));
        map.put(new PersistentDataKey("test", "int_arr"), new PersistentDataValue.IntegerArrayValue(new int[]{10, 20, 30}));
        map.put(new PersistentDataKey("test", "long_arr"), new PersistentDataValue.LongArrayValue(new long[]{100L, 200L, 300L}));

        // Nested container
        PersistentDataSnapshot nested = new PersistentDataSnapshot(Map.of(
            new PersistentDataKey("nested", "level"), new PersistentDataValue.IntegerValue(5)
        ));
        map.put(new PersistentDataKey("test", "container"), new PersistentDataValue.ContainerValue(nested));

        // Container array
        map.put(new PersistentDataKey("test", "container_arr"), new PersistentDataValue.ContainerArrayValue(List.of(nested)));

        PersistentDataSnapshot original = new PersistentDataSnapshot(map);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        PersistentDataSerializer.writeSnapshot(dos, original);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        PersistentDataSnapshot restored = PersistentDataSerializer.readSnapshot(dis);

        assertEquals(original.size(), restored.size());
        assertEquals(original, restored);
    }

    @Test
    @DisplayName("PDC: Defensive array cloning prevents mutation")
    void testDefensiveCloning() {
        byte[] rawBytes = new byte[]{1, 2, 3};
        PersistentDataValue.ByteArrayValue ba = new PersistentDataValue.ByteArrayValue(rawBytes);
        rawBytes[0] = 99;
        assertEquals((byte) 1, ba.value()[0]);

        byte[] retrieved = ba.value();
        retrieved[0] = 77;
        assertEquals((byte) 1, ba.value()[0]);
    }
}
