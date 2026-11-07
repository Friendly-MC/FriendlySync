package dev.lyy.friendlysync.lock;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.util.Preconditions;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces interaction restrictions on players during pre-login, loading, synchronization, and transfer phases.
 * Prevents item dropping, picking up, container interaction, and damage until session is ACTIVE.
 */
public final class JoinLockService {

    private final PlayerSessionService sessionService;
    private final Set<UUID> explicitlyLocked = ConcurrentHashMap.newKeySet();

    public JoinLockService(PlayerSessionService sessionService) {
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
    }

    /**
     * Determines whether a player is locked and restricted from world/inventory mutations.
     *
     * @param uuid player UUID
     * @return true if locked, false if fully active
     */
    public boolean isLocked(UUID uuid) {
        if (!explicitlyLocked.isEmpty() && explicitlyLocked.contains(uuid)) {
            return true;
        }

        ActivePlayerSession session = sessionService.getLocalSessionRaw(uuid);
        if (session == null) {
            return true; // Not registered locally, lock by default
        }

        return session.getState() != SessionState.ACTIVE;
    }

    public void setExplicitlyLocked(UUID uuid, boolean locked) {
        if (locked) {
            explicitlyLocked.add(uuid);
        } else {
            explicitlyLocked.remove(uuid);
        }
    }

    public void remove(UUID uuid) {
        explicitlyLocked.remove(uuid);
    }
}
