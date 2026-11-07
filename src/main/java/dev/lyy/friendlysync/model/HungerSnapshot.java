package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of player food, saturation, and exhaustion levels.
 */
public record HungerSnapshot(
    int foodLevel,
    float saturation,
    float exhaustion
) {
}
