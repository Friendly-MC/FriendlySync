package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of player health and absorption data.
 */
public record VitalSnapshot(
    double health,
    double maxHealth,
    double absorption
) {
}
