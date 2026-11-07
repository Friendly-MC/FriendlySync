package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of player coordinate location.
 */
public record LocationSnapshot(
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
}
