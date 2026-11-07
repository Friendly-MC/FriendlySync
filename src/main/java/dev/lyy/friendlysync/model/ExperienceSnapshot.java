package dev.lyy.friendlysync.model;

/**
 * Immutable snapshot of player experience data.
 */
public record ExperienceSnapshot(
    int level,
    float expProgress,
    int totalExperience
) {
}
