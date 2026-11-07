package dev.lyy.friendlysync.model;

import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.util.Preconditions;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable composite snapshot capturing the complete synchronized state of a player at a specific revision.
 */
public record PlayerStateSnapshot(
    UUID playerUuid,
    long revision,
    long fencingToken,
    InventorySnapshot inventory,
    EnderChestSnapshot enderChest,
    ExperienceSnapshot experience,
    VitalSnapshot vitals,
    HungerSnapshot hunger,
    List<EffectSnapshot> effects,
    GameModeSnapshot gameMode,
    FlightSnapshot flight,
    LocationSnapshot location,
    PersistentDataSnapshot persistentData,
    StatisticsSnapshot statistics,
    Map<String, byte[]> customModuleData,
    Instant capturedAt,
    int schemaVersion
) {
    public static final int CURRENT_SCHEMA_VERSION = 3;

    public PlayerStateSnapshot {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        effects = effects != null ? List.copyOf(effects) : Collections.emptyList();
        persistentData = persistentData != null ? persistentData : PersistentDataSnapshot.EMPTY;
        statistics = statistics != null ? statistics : StatisticsSnapshot.EMPTY;
        customModuleData = customModuleData != null ? Map.copyOf(customModuleData) : Collections.emptyMap();
        capturedAt = capturedAt != null ? capturedAt : Instant.now();
    }

    /**
     * Backwards-compatible constructor without PDC and statistics.
     */
    public PlayerStateSnapshot(
        UUID playerUuid,
        long revision,
        long fencingToken,
        InventorySnapshot inventory,
        EnderChestSnapshot enderChest,
        ExperienceSnapshot experience,
        VitalSnapshot vitals,
        HungerSnapshot hunger,
        List<EffectSnapshot> effects,
        GameModeSnapshot gameMode,
        FlightSnapshot flight,
        LocationSnapshot location,
        Map<String, byte[]> customModuleData,
        Instant capturedAt,
        int schemaVersion
    ) {
        this(
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
            PersistentDataSnapshot.EMPTY,
            StatisticsSnapshot.EMPTY,
            customModuleData,
            capturedAt,
            schemaVersion
        );
    }

    /**
     * Compares core player state contents (inventory, ender chest, stats, vitals, etc.)
     * ignoring only revision numbers, fencing tokens, and capture timestamps.
     */
    public boolean hasIdenticalState(PlayerStateSnapshot other) {
        if (other == null) return false;
        if (this == other) return true;
        return java.util.Objects.equals(this.inventory, other.inventory)
            && java.util.Objects.equals(this.enderChest, other.enderChest)
            && java.util.Objects.equals(this.experience, other.experience)
            && java.util.Objects.equals(this.vitals, other.vitals)
            && java.util.Objects.equals(this.hunger, other.hunger)
            && java.util.Objects.equals(this.effects, other.effects)
            && java.util.Objects.equals(this.gameMode, other.gameMode)
            && java.util.Objects.equals(this.flight, other.flight)
            && java.util.Objects.equals(this.location, other.location)
            && java.util.Objects.equals(this.persistentData, other.persistentData)
            && java.util.Objects.equals(this.statistics, other.statistics)
            && this.customModuleData.keySet().equals(other.customModuleData.keySet())
            && this.customModuleData.entrySet().stream().allMatch(entry ->
                java.util.Arrays.equals(entry.getValue(), other.customModuleData.get(entry.getKey())));
    }
}
