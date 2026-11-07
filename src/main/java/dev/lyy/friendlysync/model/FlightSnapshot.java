package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of player flight and physical states.
 */
public record FlightSnapshot(
    boolean allowFlight,
    boolean isFlying,
    int fireTicks,
    int remainingAir,
    float fallDistance
) {
}
