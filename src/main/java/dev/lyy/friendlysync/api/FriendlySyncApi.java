package dev.lyy.friendlysync.api;

import dev.lyy.friendlysync.model.result.SaveResult;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Public developer API for interacting with FriendlySync.
 */
public interface FriendlySyncApi {

    /**
     * Gets the runtime status of the specified player.
     */
    CompletionStage<PlayerSyncStatus> status(UUID playerUuid);

    /**
     * Loads a read-only view of the persisted player state from durable storage.
     */
    CompletionStage<PlayerStateView> load(UUID playerUuid);

    /**
     * Requests an immediate state commit for the player if dirty.
     */
    CompletionStage<SaveResult> requestSave(UUID playerUuid);

    /**
     * Checks if the player's data has loaded, the session is ACTIVE, and join/restore locks have cleared.
     */
    boolean isPlayerReady(UUID playerUuid);

    /**
     * Gets a read-only view of the local active session for this player if present.
     */
    Optional<SessionView> session(UUID playerUuid);

    /**
     * Registers an external custom player state synchronization module.
     */
    void registerModule(CustomSyncModule<?> module);

    /**
     * Explicitly marks a player's session dirty (e.g. after a third-party plugin inventory modification).
     */
    void markDirty(UUID playerUuid, SyncReason reason);

    /**

     * Loads a read-only view of the player's synchronized PersistentDataContainer.
     */
    CompletionStage<Optional<PersistentDataView>> persistentData(UUID playerUuid);

    /**
     * Loads a read-only view of the player's synchronized statistics.
     */
    CompletionStage<Optional<StatisticsView>> statistics(UUID playerUuid);
}
