package dev.lyy.friendlysync.serialization;

import dev.lyy.friendlysync.model.PlayerStateSnapshot;

/**
 * Dedicated contract for serializing and deserializing complete immutable player snapshots.
 */
public interface PlayerStateSerializer {

    /**
     * Serializes a snapshot into a compressed, integrity-protected byte array.
     */
    byte[] serialize(PlayerStateSnapshot snapshot);

    /**
     * Deserializes a byte array back into an immutable PlayerStateSnapshot.
     */
    PlayerStateSnapshot deserialize(byte[] data);
}
