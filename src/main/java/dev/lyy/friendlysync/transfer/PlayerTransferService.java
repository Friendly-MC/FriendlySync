package dev.lyy.friendlysync.transfer;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.api.event.FriendlySyncTransferCompleteEvent;
import dev.lyy.friendlysync.api.event.FriendlySyncTransferStartEvent;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.TransferLogRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.messaging.message.TransferReadyMessage;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.model.result.TransferResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.scheduler.ScheduledTaskHandle;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Save and release before proxy dispatch; the final-save journal protects failed commits. */
public final class PlayerTransferService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerTransferService.class);
    private final Plugin plugin;
    private final FriendlySyncConfig config;
    private final PlatformScheduler scheduler;
    private final PlayerOperationQueue operationQueue;
    private final PlayerSessionService sessionService;
    private final PlayerOwnershipService ownershipService;
    private final SyncModuleManager syncModuleManager;
    private final PlayerStateService stateService;
    private final TransferLogRepository transferLogRepository;
    private final MessagingService messagingService;
    private final TransferFreezeService freezeService;
    private final JoinLockService joinLockService;
    private final Map<UUID, CompletableFuture<TransferResult>> inFlight = new ConcurrentHashMap<>();

    public PlayerTransferService(Plugin plugin, FriendlySyncConfig config, PlatformScheduler scheduler,
                                 PlayerOperationQueue operationQueue, PlayerSessionService sessionService,
                                 PlayerOwnershipService ownershipService, SyncModuleManager syncModuleManager,
                                 PlayerStateService stateService, TransferLogRepository transferLogRepository,
                                 MessagingService messagingService, TransferFreezeService freezeService,
                                 JoinLockService joinLockService) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = scheduler;
        this.operationQueue = operationQueue;
        this.sessionService = sessionService;
        this.ownershipService = ownershipService;
        this.syncModuleManager = syncModuleManager;
        this.stateService = stateService;
        this.transferLogRepository = transferLogRepository;
        this.messagingService = messagingService;
        this.freezeService = freezeService;
        this.joinLockService = joinLockService;
    }

    /** Includes pending durable work even after the caller's timeout. Never cancel this future. */
    public CompletableFuture<TransferResult> getInFlightTransfer(UUID uuid) {
        return inFlight.get(uuid);
    }

    public java.util.Collection<CompletableFuture<TransferResult>> getInFlightTransfers() {
        return java.util.List.copyOf(inFlight.values());
    }

    public CompletableFuture<TransferResult> transfer(Player player, String targetServer) {
        return transfer(player, targetServer, null);
    }

    public CompletableFuture<TransferResult> transferRtp(Player player, String targetServer, UUID requestId) {
        if (requestId == null) return failed("Missing RTP request identity");
        return transfer(player, targetServer, requestId);
    }

    private CompletableFuture<TransferResult> transfer(Player player, String targetServer, UUID rtpRequest) {
        if (player == null || targetServer == null || targetServer.isBlank()
            || targetServer.equalsIgnoreCase(config.getServerId())) {
            return failed("Invalid transfer destination");
        }
        UUID uuid = player.getUniqueId();
        var session = sessionService.getLocalSession(uuid).orElse(null);
        if (session == null || session.getState() != SessionState.ACTIVE) return failed("Player session is not ACTIVE");
        if (player.isDead() || player.getHealth() <= 0) return failed("Player cannot transfer while dead or dying");
        CompletableFuture<TransferResult> durable = new CompletableFuture<>();
        if (inFlight.putIfAbsent(uuid, durable) != null) return failed("Transfer already in progress");
        CompletableFuture<TransferResult> result = new CompletableFuture<>();
        UUID transferId = UUID.randomUUID();
        ScheduledTaskHandle timeout;
        try {
            timeout = scheduler.runAsyncLater(config.getTransferTimeout(), () -> {
                if (result.complete(new TransferResult.TimedOut(targetServer))) {
                    scheduler.runForPlayer(player, () -> {
                        if (sessionService.getLocalSession(uuid).orElse(null) == session
                            && session.getState() != SessionState.ACTIVE) {
                            player.kick(Component.text("Your transfer timed out safely. Please reconnect."));
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(uuid, durable);
            return CompletableFuture.failedFuture(e);
        }
        result.whenComplete((value, error) -> { if (timeout != null) timeout.cancel(); });
        durable.whenComplete((value, error) -> {
            inFlight.remove(uuid, durable);
            if (error != null) result.completeExceptionally(error);
            else result.complete(value);
        });
        // Keep this queue slot until capture/commit/release settles, even when the caller times out.
        operationQueue.submit(uuid, () -> {
            CompletableFuture<TransferResult> work = new CompletableFuture<>();
            if (result.isDone()) return failed("Transfer expired before capture");
            transferLogRepository.logTransferStart(transferId, uuid, session.getSessionId(),
                config.getServerId(), targetServer, session.getRevision());
            scheduler.runForPlayer(player, () -> {
                if (result.isDone() || !player.isOnline()
                    || sessionService.getLocalSession(uuid).orElse(null) != session
                    || session.getState() != SessionState.ACTIVE) {
                    work.complete(new TransferResult.Failed("Player session changed before transfer", null));
                    return;
                }
                try {
                    Bukkit.getPluginManager().callEvent(new FriendlySyncTransferStartEvent(player, targetServer));
                    if (!freezeService.freeze(player)) {
                        work.complete(new TransferResult.Failed("Failed to freeze player", null));
                        return;
                    }
                    var snapshot = syncModuleManager.captureSnapshot(player, session.getRevision(),
                        session.getFencingToken(), SyncReason.TRANSFER, session);
                    // FREEZING makes quit defer to this captured final save until ownership is released.
                    try {
                        scheduler.runAsync(() -> {
                            try {
                                CommitResult commit = stateService.commitFinalSnapshotInQueue(snapshot, session.toIdentity());
                                if (!(commit instanceof CommitResult.Success success)) {
                                    throw new IllegalStateException("Final transfer commit failed: " + commit);
                                }
                                session.setRevision(success.newRevision());
                                session.transitionTo(SessionState.SAVING);
                                session.transitionTo(SessionState.TRANSFER_PENDING);
                                if (!ownershipService.releaseOwnership(session.toIdentity(), "RELEASED")) {
                                    throw new IllegalStateException("Ownership release failed after final commit");
                                }
                                session.forceState(SessionState.RELEASED);
                                if (sessionService.isPlayerOnline(uuid)) joinLockService.setExplicitlyLocked(uuid, true);
                                // SQL already confirms the handoff. Redis notification is only an optimization.
                                try {
                                    messagingService.publishTransferReady(TransferReadyMessage.create(uuid,
                                        success.newRevision(), session.getFencingToken(), config.getServerId(), targetServer));
                                } catch (RuntimeException e) {
                                    LOGGER.warn("[TRANSFER] Redis notification failed for {}; SQL handoff is confirmed", uuid, e);
                                }
                                transferLogRepository.logTransferComplete(transferId, success.newRevision(), "SUCCESS");
                                scheduler.runForPlayer(player, () -> {
                                    if (result.isDone() || !player.isOnline()) {
                                        work.complete(new TransferResult.Failed("Transfer expired after save", null));
                                        if (player.isOnline()) player.kick(Component.text("Transfer expired. Please reconnect."));
                                        return;
                                    }
                                    try {
                                        Bukkit.getPluginManager().callEvent(new FriendlySyncTransferCompleteEvent(player, targetServer, success.newRevision()));
                                        if (!sendToProxy(player, targetServer, rtpRequest)) throw new IllegalStateException("Proxy dispatch failed");
                                        scheduler.runForPlayerLater(player, config.getTransferTimeout(), () -> {
                                            if (player.isOnline()) player.kick(Component.text(rtpRequest == null
                                                ? "The proxy did not complete your transfer. Please reconnect."
                                                : "friendlycore:rtp-return:" + rtpRequest));
                                        });
                                        work.complete(new TransferResult.Success(targetServer, success.newRevision()));
                                    } catch (RuntimeException e) {
                                        work.complete(new TransferResult.Failed("Proxy dispatch failed", e));
                                        player.kick(Component.text("Your transfer could not finish. Please reconnect."));
                                    }
                                }, () -> work.complete(new TransferResult.Failed("Player disconnected after save", null)));
                            } catch (Exception e) {
                                if (session.getState() != SessionState.RELEASED) session.forceState(SessionState.FAILED);
                                LOGGER.error("[TRANSFER] Failed transfer for {}; retaining failed saves for recovery", uuid, e);
                                kickSafely(player, "Your transfer could not finish safely. Please reconnect.");
                                if (!sessionService.isPlayerOnline(uuid) && stateService.hasPendingFinalSave(uuid)) {
                                    sessionService.removeSession(session.toIdentity());
                                }
                                work.complete(new TransferResult.Failed("Final transfer failed", e));
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        stateService.retainRejectedFinalSave(snapshot, session.toIdentity());
                        throw e;
                    }
                } catch (Exception e) {
                    session.forceState(SessionState.FAILED);
                    kickSafely(player, "Unable to capture your transfer safely. Please reconnect.");
                    work.complete(new TransferResult.Failed("Transfer capture failed", e));
                }
            }, () -> work.complete(new TransferResult.Failed("Player disconnected before capture", null)));
            return work;
        }).whenComplete((value, error) -> {
            if (error != null) durable.completeExceptionally(error);
            else durable.complete(value);
        });
        return result;
    }

    private static CompletableFuture<TransferResult> failed(String reason) {
        return CompletableFuture.completedFuture(new TransferResult.Failed(reason, null));
    }

    private void kickSafely(Player player, String message) {
        try {
            scheduler.runForPlayer(player, () -> { if (player.isOnline()) player.kick(Component.text(message)); });
        } catch (RuntimeException e) {
            LOGGER.warn("Could not dispatch disconnect for a locked transfer", e);
        }
    }

    /** Caller must confirm final save and ownership release first, on the player's scheduler. */
    public boolean sendToProxy(Player player, String serverName) {
        return sendToProxy(player, serverName, null);
    }

    private boolean sendToProxy(Player player, String serverName, UUID rtpRequest) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (rtpRequest != null) {
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeByte(1);
                    out.writeLong(rtpRequest.getMostSignificantBits());
                    out.writeLong(rtpRequest.getLeastSignificantBits());
                    out.writeUTF(serverName);
                }
                player.sendPluginMessage(plugin, "friendlycore:rtp_return", bytes.toByteArray());
                bytes = new ByteArrayOutputStream();
            }
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("Connect");
                out.writeUTF(serverName);
            }
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed proxy dispatch for {}: {}", player.getName(), e.getMessage());
            return false;
        }
    }
}
