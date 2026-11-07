package dev.lyy.friendlysync.serialization;

import dev.lyy.friendlysync.model.EffectSnapshot;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.ExperienceSnapshot;
import dev.lyy.friendlysync.model.FlightSnapshot;
import dev.lyy.friendlysync.model.GameModeSnapshot;
import dev.lyy.friendlysync.model.HungerSnapshot;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.LocationSnapshot;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.VitalSnapshot;
import dev.lyy.friendlysync.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;

/**
 * Standard binary serializer for player state snapshots with schema versioning and compression.
 */
public final class DefaultPlayerStateSerializer implements PlayerStateSerializer {

    public static final int MAGIC = 0x4653594E; // "FSYN"
    public static final int CURRENT_VERSION = 3;

    private final CompressionService compressionService;

    public DefaultPlayerStateSerializer(CompressionService compressionService) {
        this.compressionService = Preconditions.checkNotNull(compressionService, "compressionService cannot be null");
    }

    @Override
    public byte[] serialize(PlayerStateSnapshot snapshot) {
        Preconditions.checkNotNull(snapshot, "snapshot cannot be null");

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(MAGIC);
                out.writeInt(CURRENT_VERSION);
                out.writeLong(snapshot.revision());
                out.writeLong(snapshot.fencingToken());
                out.writeLong(snapshot.playerUuid().getMostSignificantBits());
                out.writeLong(snapshot.playerUuid().getLeastSignificantBits());
                out.writeLong(snapshot.capturedAt().toEpochMilli());

                InventorySnapshot inv = snapshot.inventory();
                if (inv != null) {
                    out.writeBoolean(true);
                    out.writeInt(inv.heldSlot());
                    writeItemArray(out, inv.storageRaw());
                    writeItemArray(out, inv.armorRaw());
                    writeItemBytes(out, inv.offHandRaw());
                    writeItemBytes(out, inv.cursorRaw());
                } else {
                    out.writeBoolean(false);
                }

                EnderChestSnapshot ec = snapshot.enderChest();
                if (ec != null) {
                    out.writeBoolean(true);
                    writeItemArray(out, ec.itemsRaw());
                } else {
                    out.writeBoolean(false);
                }

                ExperienceSnapshot xp = snapshot.experience();
                if (xp != null) {
                    out.writeBoolean(true);
                    out.writeInt(xp.level());
                    out.writeFloat(xp.expProgress());
                    out.writeInt(xp.totalExperience());
                } else {
                    out.writeBoolean(false);
                }

                VitalSnapshot vitals = snapshot.vitals();
                if (vitals != null) {
                    out.writeBoolean(true);
                    out.writeDouble(vitals.health());
                    out.writeDouble(vitals.maxHealth());
                    out.writeDouble(vitals.absorption());
                } else {
                    out.writeBoolean(false);
                }

                HungerSnapshot hunger = snapshot.hunger();
                if (hunger != null) {
                    out.writeBoolean(true);
                    out.writeInt(hunger.foodLevel());
                    out.writeFloat(hunger.saturation());
                    out.writeFloat(hunger.exhaustion());
                } else {
                    out.writeBoolean(false);
                }

                List<EffectSnapshot> effects = snapshot.effects();
                if (effects != null && !effects.isEmpty()) {
                    out.writeInt(effects.size());
                    for (EffectSnapshot effect : effects) {
                        out.writeUTF(effect.type());
                        out.writeInt(effect.duration());
                        out.writeInt(effect.amplifier());
                        out.writeBoolean(effect.ambient());
                        out.writeBoolean(effect.particles());
                        out.writeBoolean(effect.icon());
                    }
                } else {
                    out.writeInt(0);
                }

                GameModeSnapshot gm = snapshot.gameMode();
                if (gm != null && gm.gameMode() != null) {
                    out.writeBoolean(true);
                    out.writeUTF(gm.gameMode());
                } else {
                    out.writeBoolean(false);
                }

                FlightSnapshot flight = snapshot.flight();
                if (flight != null) {
                    out.writeBoolean(true);
                    out.writeBoolean(flight.allowFlight());
                    out.writeBoolean(flight.isFlying());
                    out.writeInt(flight.fireTicks());
                    out.writeInt(flight.remainingAir());
                    out.writeFloat(flight.fallDistance());
                } else {
                    out.writeBoolean(false);
                }

                LocationSnapshot loc = snapshot.location();
                if (loc != null && loc.world() != null) {
                    out.writeBoolean(true);
                    out.writeUTF(loc.world());
                    out.writeDouble(loc.x());
                    out.writeDouble(loc.y());
                    out.writeDouble(loc.z());
                    out.writeFloat(loc.yaw());
                    out.writeFloat(loc.pitch());
                } else {
                    out.writeBoolean(false);
                }

                Map<String, byte[]> custom = snapshot.customModuleData();
                if (custom != null && !custom.isEmpty()) {
                    out.writeInt(custom.size());
                    for (Map.Entry<String, byte[]> entry : custom.entrySet()) {
                        out.writeUTF(entry.getKey());
                        byte[] cBytes = entry.getValue();
                        if (cBytes != null) {
                            out.writeInt(cBytes.length);
                            out.write(cBytes);
                        } else {
                            out.writeInt(0);
                        }
                    }
                } else {
                    out.writeInt(0);
                }

                // Legacy Gameplay Location placeholder for schema v2/v3 compatibility
                out.writeBoolean(false);

                // Schema v3+: persistent data, then statistics.
                PersistentDataSerializer.writeSnapshot(out, snapshot.persistentData());
                StatisticsSerializer.writeSnapshot(out, snapshot.statistics());
            }

            return compressionService.compress(baos.toByteArray());
        } catch (IOException e) {
            throw new SerializationException("Failed to serialize PlayerStateSnapshot", e);
        }
    }

    @Override
    public PlayerStateSnapshot deserialize(byte[] data) {
        Preconditions.checkNotNull(data, "data cannot be null");

        byte[] raw = compressionService.decompress(data);

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new SerializationException(String.format("Invalid magic header: 0x%08X (expected 0x%08X)", magic, MAGIC));
            }

            int version = in.readInt();
            if (version > CURRENT_VERSION) {
                throw new SerializationException(String.format("Unsupported snapshot schema version %d (current is %d)", version, CURRENT_VERSION));
            }

            long revision = in.readLong();
            long fencingToken = in.readLong();
            UUID playerUuid = new UUID(in.readLong(), in.readLong());
            Instant capturedAt = Instant.ofEpochMilli(in.readLong());

            InventorySnapshot inventory = null;
            if (in.readBoolean()) {
                int heldSlot = in.readInt();
                byte[][] storage = readItemArray(in);
                byte[][] armor = readItemArray(in);
                byte[] offHand = readItemBytes(in);
                byte[] cursor = readItemBytes(in);
                inventory = new InventorySnapshot(storage, armor, offHand, heldSlot, cursor);
            }

            EnderChestSnapshot enderChest = null;
            if (in.readBoolean()) {
                byte[][] items = readItemArray(in);
                enderChest = new EnderChestSnapshot(items);
            }

            ExperienceSnapshot experience = null;
            if (in.readBoolean()) {
                experience = new ExperienceSnapshot(in.readInt(), in.readFloat(), in.readInt());
            }

            VitalSnapshot vitals = null;
            if (in.readBoolean()) {
                vitals = new VitalSnapshot(in.readDouble(), in.readDouble(), in.readDouble());
            }

            HungerSnapshot hunger = null;
            if (in.readBoolean()) {
                hunger = new HungerSnapshot(in.readInt(), in.readFloat(), in.readFloat());
            }

            int effectCount = in.readInt();
            if (effectCount < 0 || effectCount > MAX_EFFECT_COUNT) {
                throw new SerializationException("Invalid potion effect count: " + effectCount);
            }
            List<EffectSnapshot> effects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                effects.add(new EffectSnapshot(
                    in.readUTF(),
                    in.readInt(),
                    in.readInt(),
                    in.readBoolean(),
                    in.readBoolean(),
                    in.readBoolean()
                ));
            }

            GameModeSnapshot gameMode = null;
            if (in.readBoolean()) {
                gameMode = new GameModeSnapshot(in.readUTF());
            }

            FlightSnapshot flight = null;
            if (in.readBoolean()) {
                flight = new FlightSnapshot(
                    in.readBoolean(),
                    in.readBoolean(),
                    in.readInt(),
                    in.readInt(),
                    in.readFloat()
                );
            }

            LocationSnapshot location = null;
            if (in.readBoolean()) {
                location = new LocationSnapshot(
                    in.readUTF(),
                    in.readDouble(),
                    in.readDouble(),
                    in.readDouble(),
                    in.readFloat(),
                    in.readFloat()
                );
            }

            int customCount = in.readInt();
            if (customCount < 0 || customCount > MAX_CUSTOM_MODULE_COUNT) {
                throw new SerializationException("Invalid custom module count: " + customCount);
            }
            Map<String, byte[]> custom = new HashMap<>(customCount);
            for (int i = 0; i < customCount; i++) {
                String key = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > MAX_CUSTOM_MODULE_BYTES) {
                    throw new SerializationException("Invalid custom module payload length: " + len);
                }
                byte[] cBytes = new byte[len];
                if (len > 0) {
                    in.readFully(cBytes);
                }
                custom.put(key, cBytes);
            }

            // Legacy Gameplay Location (Schema v2+) - skip if present for backwards compatibility
            if (version >= 2) {
                if (in.readBoolean()) {
                    in.readUTF();
                    in.readUTF();
                    in.readDouble();
                    in.readDouble();
                    in.readDouble();
                    in.readFloat();
                    in.readFloat();
                }
            }

            PersistentDataSnapshot persistentData = PersistentDataSnapshot.EMPTY;
            StatisticsSnapshot statistics = StatisticsSnapshot.EMPTY;
            if (version >= 3) {
                persistentData = PersistentDataSerializer.readSnapshot(in);
                statistics = StatisticsSerializer.readSnapshot(in);
            }

            return new PlayerStateSnapshot(
                playerUuid,
                revision,
                fencingToken,
                inventory,
                enderChest,
                experience,
                vitals,
                hunger,
                effects,
                gameMode,
                flight,
                location,
                persistentData,
                statistics,
                custom,
                capturedAt,
                version
            );
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize PlayerStateSnapshot", e);
        }
    }

    private static final int MAX_ITEM_ARRAY_LENGTH = 256;
    private static final int MAX_ITEM_BYTE_LENGTH = 10 * 1024 * 1024; // 10 MB per item max sanity limit
    private static final int MAX_EFFECT_COUNT = 256;
    private static final int MAX_CUSTOM_MODULE_COUNT = 1_024;
    private static final int MAX_CUSTOM_MODULE_BYTES = 16 * 1024 * 1024;

    private static void writeItemArray(DataOutputStream out, byte[][] items) throws IOException {
        if (items == null) {
            out.writeInt(0);
            return;
        }
        out.writeInt(items.length);
        for (byte[] item : items) {
            writeItemBytes(out, item);
        }
    }

    private static byte[][] readItemArray(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_ITEM_ARRAY_LENGTH) {
            throw new SerializationException("Corrupt item array length: " + length);
        }
        byte[][] items = new byte[length][];
        for (int i = 0; i < length; i++) {
            items[i] = readItemBytes(in);
        }
        return items;
    }

    private static void writeItemBytes(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            out.writeInt(-1);
        } else {
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static byte[] readItemBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0) {
            return null;
        }
        if (length > MAX_ITEM_BYTE_LENGTH) {
            throw new SerializationException("Corrupt item byte length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
