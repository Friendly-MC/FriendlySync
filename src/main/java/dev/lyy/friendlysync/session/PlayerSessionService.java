package dev.lyy.friendlysync.session;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory cache of active authoritative player sessions owned by this local server.
 */
public final class PlayerSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerSessionService.class);

    private final ConcurrentHashMap<UUID, ActivePlayerSession> localSessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

    public Optional<ActivePlayerSession> getLocalSession(UUID uuid) {
        return Optional.ofNullable(localSessions.get(uuid));
    }

    public ActivePlayerSession getLocalSessionRaw(UUID uuid) {
        return uuid != null ? localSessions.get(uuid) : null;
    }

    public Collection<ActivePlayerSession> getAllLocalSessions() {
        return Collections.unmodifiableCollection(localSessions.values());
    }

    public Collection<ActivePlayerSession> getLocalSessions() {
        return localSessions.values();
    }

    public ActivePlayerSession registerSession(
        SessionIdentity identity,
        long initialRevision,
        String username,
        SessionState initialState
    ) {
        Preconditions.checkNotNull(identity, "identity cannot be null");
        ActivePlayerSession session = new ActivePlayerSession(
            identity.playerUuid(),
            identity.sessionId(),
            identity.serverId(),
            identity.fencingToken(),
            initialRevision,
            username,
            initialState
        );
        localSessions.put(identity.playerUuid(), session);
        LOGGER.debug("Registered local active session for player {} (session={}, token={}, rev={})",
            identity.playerUuid(), identity.sessionId(), identity.fencingToken(), initialRevision);
        return session;
    }


    public void registerSession(ActivePlayerSession session) {
        Preconditions.checkNotNull(session, "session cannot be null");
        localSessions.put(session.getPlayerUuid(), session);
        LOGGER.debug("Registered pre-existing local active session for player {}", session.getPlayerUuid());
    }


    public Optional<ActivePlayerSession> removeSession(UUID uuid) {
        ActivePlayerSession removed = localSessions.remove(uuid);
        if (removed != null) {
            LOGGER.debug("Removed local session for player {}", uuid);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Removes a session only if it is still the exact session that the caller owns.
     * This prevents a late completion from an older, fenced session from deleting a
     * newer local login for the same player.
     */
    public boolean removeSession(SessionIdentity identity) {
        Preconditions.checkNotNull(identity, "identity cannot be null");
        return localSessions.compute(identity.playerUuid(), (uuid, current) -> {
            if (current != null
                && current.getSessionId().equals(identity.sessionId())
                && current.getFencingToken() == identity.fencingToken()) {
                LOGGER.debug("Removed exact local session for player {} (session={}, token={})",
                    uuid, identity.sessionId(), identity.fencingToken());
                return null;
            }
            return current;
        }) == null;
    }

    public boolean isOwnedLocally(UUID uuid) {
        ActivePlayerSession session = localSessions.get(uuid);
        return session != null && session.getState() != SessionState.OFFLINE && session.getState() != SessionState.RELEASED;
    }

    public void invalidateLocalSession(UUID uuid, long newerToken) {
        ActivePlayerSession session = localSessions.get(uuid);
        if (session != null && session.getFencingToken() < newerToken) {
            LOGGER.warn("[SESSION] Local session for player {} superseded by newer token {}. Marking OFFLINE.",
                uuid, newerToken);
            session.forceState(SessionState.OFFLINE);
            localSessions.remove(uuid);
        }
    }

    public int getActiveSessionCount() {
        return localSessions.size();
    }

    public boolean hasActiveSessions() {
        return !localSessions.isEmpty();
    }

    public void markPlayerOnline(UUID uuid) {
        onlinePlayers.add(Preconditions.checkNotNull(uuid, "uuid cannot be null"));
    }

    public void markPlayerOffline(UUID uuid) {
        if (uuid != null) {
            onlinePlayers.remove(uuid);
        }
    }

    public boolean isPlayerOnline(UUID uuid) {
        return uuid != null && onlinePlayers.contains(uuid);
    }
}
