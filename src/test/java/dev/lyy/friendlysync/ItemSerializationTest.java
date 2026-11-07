package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.serialization.CompressionAlgorithm;
import dev.lyy.friendlysync.serialization.CompressionService;
import dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class ItemSerializationTest {

    private PlayerStateSerializer serializer;

    @BeforeEach
    void setUp() {
        CompressionService compressionService = new CompressionService(CompressionAlgorithm.NONE, 0);
        serializer = new DefaultPlayerStateSerializer(compressionService);
    }

    @Test
    @DisplayName("Item Regression (Section 86): Complex inventory arrays preserve byte exactness across serialization")
    void testInventoryByteExactness() {
        byte[][] storage = new byte[36][];
        // Simulate complex NBT item byte sequences (e.g. enchanted netherite sword, shulker box with items)
        storage[0] = new byte[]{(byte) 0x0A, (byte) 0x00, (byte) 0x01, (byte) 0x7F, (byte) 0xFF};
        storage[8] = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE};

        byte[][] armor = new byte[4][];
        armor[0] = new byte[]{(byte) 0x11, (byte) 0x22}; // Boots
        armor[3] = new byte[]{(byte) 0x33, (byte) 0x44}; // Helmet

        byte[] offhand = new byte[]{(byte) 0x55, (byte) 0x66};
        byte[] cursor = new byte[]{(byte) 0x77, (byte) 0x88};

        InventorySnapshot originalInv = new InventorySnapshot(storage, armor, offhand, 3, cursor);

        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
            UUID.randomUUID(),
            100L,
            50L,
            originalInv,
            null, null, null, null, null, null, null, null, null,
            Instant.now(),
            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
        );

        byte[] serialized = serializer.serialize(snapshot);
        PlayerStateSnapshot deserialized = serializer.deserialize(serialized);

        InventorySnapshot restoredInv = deserialized.inventory();
        assertThat(restoredInv).isNotNull();
        assertThat(restoredInv.heldSlot()).isEqualTo(3);
        assertThat(restoredInv.storage()[0]).isEqualTo(storage[0]);
        assertThat(restoredInv.storage()[8]).isEqualTo(storage[8]);
        assertThat(restoredInv.storage()[1]).isNull();
        assertThat(restoredInv.armor()[0]).isEqualTo(armor[0]);
        assertThat(restoredInv.armor()[3]).isEqualTo(armor[3]);
        assertThat(restoredInv.offHand()).isEqualTo(offhand);
        assertThat(restoredInv.cursor()).isEqualTo(cursor);
    }
}
