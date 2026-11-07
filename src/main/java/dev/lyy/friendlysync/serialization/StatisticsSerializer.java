package dev.lyy.friendlysync.serialization;

import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticQualifierType;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Binary serializer for player StatisticsSnapshot.
 */
public final class StatisticsSerializer {

    public static final int MAX_ENTRIES = 65_536;

    private StatisticsSerializer() {
    }

    public static void writeSnapshot(DataOutputStream out, StatisticsSnapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.isEmpty()) {
            out.writeInt(0);
            return;
        }

        Map<StatisticKey, Long> values = snapshot.values();
        out.writeInt(values.size());

        for (Map.Entry<StatisticKey, Long> entry : values.entrySet()) {
            StatisticKey key = entry.getKey();
            out.writeUTF(key.statistic());

            StatisticQualifierType qType = key.qualifierType();
            byte qByte = switch (qType) {
                case NONE -> (byte) 0;
                case MATERIAL -> (byte) 1;
                case ENTITY -> (byte) 2;
            };
            out.writeByte(qByte);

            if (qType != StatisticQualifierType.NONE && key.qualifier() != null) {
                out.writeUTF(key.qualifier());
            }

            out.writeLong(entry.getValue());
        }
    }

    public static StatisticsSnapshot readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new SerializationException("Invalid statistics entry count: " + count);
        }
        if (count == 0) {
            return StatisticsSnapshot.EMPTY;
        }

        Map<StatisticKey, Long> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            String statistic = in.readUTF();
            byte qByte = in.readByte();
            StatisticQualifierType qType = switch (qByte) {
                case 1 -> StatisticQualifierType.MATERIAL;
                case 2 -> StatisticQualifierType.ENTITY;
                default -> StatisticQualifierType.NONE;
            };

            String qualifier = null;
            if (qType != StatisticQualifierType.NONE) {
                qualifier = in.readUTF();
            }

            long value = in.readLong();
            map.put(new StatisticKey(statistic, qType, qualifier), value);
        }

        return new StatisticsSnapshot(map);
    }
}
