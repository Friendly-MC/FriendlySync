package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of an active potion effect.
 */
public record EffectSnapshot(
    String type,
    int duration,
    int amplifier,
    boolean ambient,
    boolean particles,
    boolean icon
) {
}
