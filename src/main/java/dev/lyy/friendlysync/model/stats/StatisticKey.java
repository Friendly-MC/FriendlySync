package dev.lyy.friendlysync.model.stats;

import dev.lyy.friendlysync.util.Preconditions;

import java.util.Objects;

/**
 * Stable identifier for a player statistic, avoiding ordinal indexes.
 */
public record StatisticKey(
    String statistic,
    StatisticQualifierType qualifierType,
    String qualifier
) {

    public StatisticKey {
        Preconditions.checkNotNull(statistic, "statistic cannot be null");
        qualifierType = qualifierType != null ? qualifierType : StatisticQualifierType.NONE;
        statistic = statistic.toLowerCase();
        qualifier = qualifier != null ? qualifier.toLowerCase() : null;
        if (qualifierType == StatisticQualifierType.NONE && qualifier != null) {
            qualifier = null;
        }
    }

    public static StatisticKey untyped(String statistic) {
        return new StatisticKey(statistic, StatisticQualifierType.NONE, null);
    }

    public static StatisticKey material(String statistic, String materialKey) {
        Preconditions.checkNotNull(materialKey, "materialKey cannot be null");
        return new StatisticKey(statistic, StatisticQualifierType.MATERIAL, materialKey);
    }

    public static StatisticKey entity(String statistic, String entityKey) {
        Preconditions.checkNotNull(entityKey, "entityKey cannot be null");
        return new StatisticKey(statistic, StatisticQualifierType.ENTITY, entityKey);
    }

    @Override
    public String toString() {
        if (qualifierType == StatisticQualifierType.NONE || qualifier == null) {
            return statistic;
        }
        return statistic + "[" + qualifierType.name().toLowerCase() + "=" + qualifier + "]";
    }
}
