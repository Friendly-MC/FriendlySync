package dev.lyy.friendlysync.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents the lifecycle states of a player session.
 * Transitions are strictly validated to prevent invalid state jumps.
 */
public enum SessionState {
    OFFLINE,
    ACQUIRING,
    LOADING,
    ACTIVE,
    FREEZING,
    SAVING,
    TRANSFER_PENDING,
    RELEASED,
    FAILED,
    RECOVERING;

    private static final Map<SessionState, Set<SessionState>> VALID_TRANSITIONS = Map.ofEntries(
        Map.entry(OFFLINE, EnumSet.of(ACQUIRING, RECOVERING)),
        Map.entry(ACQUIRING, EnumSet.of(LOADING, FAILED, OFFLINE, RECOVERING)),
        Map.entry(LOADING, EnumSet.of(ACTIVE, FAILED, OFFLINE, RECOVERING)),
        Map.entry(ACTIVE, EnumSet.of(FREEZING, SAVING, FAILED, OFFLINE)),
        Map.entry(FREEZING, EnumSet.of(SAVING, ACTIVE, FAILED)),
        Map.entry(SAVING, EnumSet.of(TRANSFER_PENDING, RELEASED, ACTIVE, FAILED, OFFLINE)),
        Map.entry(TRANSFER_PENDING, EnumSet.of(RELEASED, FAILED, OFFLINE)),
        Map.entry(RELEASED, EnumSet.of(OFFLINE, ACQUIRING)),
        Map.entry(FAILED, EnumSet.of(RECOVERING, OFFLINE)),
        Map.entry(RECOVERING, EnumSet.of(LOADING, FAILED, OFFLINE))
    );

    /**
     * Checks if transitioning from this state to the target state is legally permitted.
     *
     * @param target the desired target state
     * @return true if valid, false otherwise
     */
    public boolean canTransitionTo(SessionState target) {
        if (this == target) {
            return true;
        }
        Set<SessionState> allowed = VALID_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }
}
