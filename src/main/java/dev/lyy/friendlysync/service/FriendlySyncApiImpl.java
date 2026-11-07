package dev.lyy.friendlysync.service;

import dev.lyy.friendlysync.api.CustomSyncModule;
import dev.lyy.friendlysync.api.FriendlySyncApi;
import dev.lyy.friendlysync.api.PlayerStateView;
import dev.lyy.friendlysync.api.PlayerSyncStatus;
import dev.lyy.friendlysync.api.SessionView;
import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.database.repository.PersistedPlayerState;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.SaveResult;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.Preconditions;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Standard implementation of the public FriendlySyncApi.
 */
public final class FriendlySyncApiImpl implements FriendlySyncApi {

    private final PlayerSessionService sessionService;
    private final PlayerStateService stateService;
    private final PlayerStateRepository repository;
    private final SyncModuleManager syncModuleManager;
    private final DirtyTrackingService dirtyTrackingService;
    private final dev.lyy.friendlysync.serialization.PlayerStateSerializer serializer;
    private final dev.lyy.friendlysync.lock.JoinLockService joinLockService;

    public FriendlySyncApiImpl(
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        PlayerStateRepository repository,
        SyncModuleManager syncModuleManager,
        DirtyTrackingService dirtyTrackingService,
        dev.lyy.friendlysync.serialization.PlayerStateSerializer serializer
    ) {
        this(sessionService, stateService, repository, syncModuleManager, dirtyTrackingService, serializer, null);
    }

    public FriendlySyncApiImpl(
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        PlayerStateRepository repository,
        SyncModuleManager syncModuleManager,
        DirtyTrackingService dirtyTrackingService,
        dev.lyy.friendlysync.serialization.PlayerStateSerializer serializer,
        dev.lyy.friendlysync.lock.JoinLockService joinLockService
    ) {
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.stateService = Preconditions.checkNotNull(stateService, "stateService cannot be null");
        this.repository = Preconditions.checkNotNull(repository, "repository cannot be null");
        this.syncModuleManager = Preconditions.checkNotNull(syncModuleManager, "syncModuleManager cannot be null");
        this.dirtyTrackingService = Preconditions.checkNotNull(dirtyTrackingService, "dirtyTrackingService cannot be null");
        this.serializer = Preconditions.checkNotNull(serializer, "serializer cannot be null");
        this.joinLockService = joinLockService;
    }

    @Override
    public CompletionStage<PlayerSyncStatus> status(UUID playerUuid) {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");

        Optional<ActivePlayerSession> localOpt = sessionService.getLocalSession(playerUuid);
        if (localOpt.isPresent()) {
            ActivePlayerSession s = localOpt.get();
            return CompletableFuture.completedFuture(new PlayerSyncStatus(
                playerUuid,
                true,
                s.getState(),
                s.getRevision(),
                s.getFencingToken(),
                s.isDirty()
            ));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PersistedPlayerState> persisted = repository.find(playerUuid);
                if (persisted.isPresent()) {
                    PersistedPlayerState p = persisted.get();
                    SessionState state;
                    try {
                        state = SessionState.valueOf(p.sessionState());
                    } catch (Exception e) {
                        state = SessionState.OFFLINE;
                    }
                    return new PlayerSyncStatus(playerUuid, false, state, p.revision(), p.fencingToken(), false);
                }
                return new PlayerSyncStatus(playerUuid, false, SessionState.OFFLINE, 0, 0, false);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<PlayerStateView> load(UUID playerUuid) {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PersistedPlayerState> persisted = repository.find(playerUuid);
                return persisted.map(p -> new PlayerStateView(
                    p.uuid(),
                    p.revision(),
                    p.fencingToken(),
                    p.ownerServer(),
                    p.updatedAt()
                )).orElse(null);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<SaveResult> requestSave(UUID playerUuid) {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        return stateService.saveState(playerUuid, SyncReason.MANUAL, false)
            .thenApply(res -> {
                if (res instanceof CommitResult.Success success) {
                    return new SaveResult.Saved(success.newRevision(), success.durationMs());
                } else if (res instanceof CommitResult.Conflict conflict) {
                    return new SaveResult.Conflict(conflict.reason());
                } else if (res instanceof CommitResult.Failed failed) {
                    return new SaveResult.Failed(failed.cause());
                }
                return new SaveResult.Failed(new IllegalStateException("Unknown commit result"));
            });
    }

    @Override
    public boolean isPlayerReady(UUID playerUuid) {
        return sessionService.getLocalSession(playerUuid)
            .map(s -> s.getState() == SessionState.ACTIVE && !syncModuleManager.isLocationTeleportPending(playerUuid)
                && (joinLockService == null || !joinLockService.isLocked(playerUuid)))
            .orElse(false);
    }

    @Override
    public Optional<SessionView> session(UUID playerUuid) {
        return sessionService.getLocalSession(playerUuid).map(s -> new SessionView(
            s.getPlayerUuid(),
            s.getSessionId(),
            s.getServerId(),
            s.getFencingToken(),
            s.getRevision(),
            s.getState(),
            s.getLastHeartbeat()
        ));
    }

    @Override
    public void registerModule(CustomSyncModule<?> module) {
        syncModuleManager.registerCustomModule(module);
    }

    @Override
    public void markDirty(UUID playerUuid, SyncReason reason) {
        dirtyTrackingService.markDirty(playerUuid);
    }

    @Override
    public CompletionStage<Optional<dev.lyy.friendlysync.api.PersistentDataView>> persistentData(UUID playerUuid) {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PersistedPlayerState> persisted = repository.find(playerUuid);
                if (persisted.isPresent() && persisted.get().payload() != null) {
                    dev.lyy.friendlysync.model.PlayerStateSnapshot snapshot = serializer.deserialize(persisted.get().payload());
                    return Optional.of(new dev.lyy.friendlysync.api.PersistentDataView(snapshot.persistentData()));
                }
                return Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletionStage<Optional<dev.lyy.friendlysync.api.StatisticsView>> statistics(UUID playerUuid) {
        Preconditions.checkNotNull(playerUuid, "playerUuid cannot be null");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<PersistedPlayerState> persisted = repository.find(playerUuid);
                if (persisted.isPresent() && persisted.get().payload() != null) {
                    dev.lyy.friendlysync.model.PlayerStateSnapshot snapshot = serializer.deserialize(persisted.get().payload());
                    return Optional.of(new dev.lyy.friendlysync.api.StatisticsView(snapshot.statistics()));
                }
                return Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
