package dev.lyy.friendlysync.command;

import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.database.repository.PersistedPlayerState;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.ServerPresenceRepository;
import dev.lyy.friendlysync.metrics.HealthReport;
import dev.lyy.friendlysync.metrics.HealthService;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.ServerInfo;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.util.DigestUtils;
import dev.lyy.friendlysync.util.Preconditions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Command handler for /friendlysync administration commands.
 */
public final class FriendlySyncCommand implements CommandExecutor, TabCompleter {

    private enum Subcommand {
        STATUS("status", 1, 1, "status", "Cluster overview"),
        HEALTH("health", 1, 1, "health", "Health diagnostics"),
        SERVERS("servers", 1, 1, "servers", "List cluster servers"),
        PLAYER("player", 2, 2, "player <name|uuid>", "Inspect player state"),
        SAVE("save", 2, 2, "save <name>", "Commit a local player's state"),
        UNLOCK("unlock", 2, 2, "unlock <name|uuid>", "Release expired ownership after investigation"),
        SNAPSHOTS("player", 2, 2, "snapshots <name|uuid>", "List snapshot history"),
        RESTORE(null, 2, 3, "restore <name|uuid> [snapshot-id|latest]", "Restore a saved profile"),
        RECOVERY("recovery", 2, 3, "recovery <uuid|name|all> [accept-current]", "Review a retained final-save conflict"),
        BULKRECOVERY("recovery", 1, 2, "bulkrecovery [accept-current]", "Resolve all retained final-save conflicts in bulk"),
        RECOVERALL("recovery", 1, 2, "recoverall [accept-current]", "Resolve all retained final-save conflicts in bulk"),
        RELOAD("reload", 1, 1, "reload", "Explain restart requirements");

        private final String permission;
        private final int minArgs;
        private final int maxArgs;
        private final String usage;
        private final String description;

        Subcommand(String permission, int minArgs, int maxArgs, String usage, String description) {
            this.permission = permission == null ? null : "friendlysync.command." + permission;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.usage = usage;
            this.description = description;
        }

        private String commandName() {
            return name().toLowerCase(Locale.ROOT);
        }

        private boolean allowed(CommandSender sender) {
            return sender.hasPermission("friendlysync.admin") || (permission != null && sender.hasPermission(permission));
        }
    }

    private final PlayerSessionService sessionService;
    private final PlayerStateService stateService;
    private final HealthService healthService;
    private final ServerPresenceRepository serverPresenceRepository;
    private final PlatformScheduler scheduler;
    private final PlayerStateRepository playerStateRepository;
    private final PlayerStateSerializer serializer;

    public FriendlySyncCommand(
        PlayerSessionService sessionService,
        PlayerStateService stateService,
        HealthService healthService,
        ServerPresenceRepository serverPresenceRepository,
        PlatformScheduler scheduler,
        PlayerStateRepository playerStateRepository,
        PlayerStateSerializer serializer
    ) {
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.stateService = Preconditions.checkNotNull(stateService, "stateService cannot be null");
        this.healthService = Preconditions.checkNotNull(healthService, "healthService cannot be null");
        this.serverPresenceRepository = Preconditions.checkNotNull(serverPresenceRepository, "serverPresenceRepository cannot be null");
        this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler cannot be null");
        this.playerStateRepository = Preconditions.checkNotNull(playerStateRepository, "playerStateRepository cannot be null");
        this.serializer = Preconditions.checkNotNull(serializer, "serializer cannot be null");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, @NotNull String[] args) {
        if (!canUse(sender)) {
            send(sender, Component.text("You lack permission to use FriendlySync commands.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (args.length > 1) send(sender, Component.text("Usage: /" + label + " help", NamedTextColor.YELLOW));
            else sendHelp(sender);
            return true;
        }

        Subcommand sub;
        try {
            sub = Subcommand.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            send(sender, Component.text("Unknown subcommand: " + args[0] + ". Use /" + label + " help.", NamedTextColor.RED));
            return true;
        }
        if (!sub.allowed(sender)) {
            send(sender, Component.text("You lack permission to use " + sub.commandName() + ".", NamedTextColor.RED));
            return true;
        }
        if (args.length < sub.minArgs || args.length > sub.maxArgs) {
            send(sender, Component.text("Usage: /" + label + " " + sub.usage, NamedTextColor.YELLOW));
            return true;
        }
        switch (sub) {
            case STATUS -> handleStatus(sender);
            case HEALTH -> handleHealth(sender);
            case SERVERS -> handleServers(sender);
            case PLAYER -> handlePlayer(sender, args);
            case SAVE -> handleSave(sender, args);
            case UNLOCK -> handleUnlock(sender, args);
            case SNAPSHOTS -> handleSnapshots(sender, args);
            case RESTORE -> handleRestore(sender, args);
            case RECOVERY -> handleRecovery(sender, args);
            case BULKRECOVERY, RECOVERALL -> {
                boolean acceptCurrent = args.length == 2 && (args[1].equalsIgnoreCase("accept-current")
                    || args[1].equalsIgnoreCase("confirm") || args[1].equalsIgnoreCase("force"));
                handleBulkRecovery(sender, acceptCurrent);
            }
            case RELOAD -> handleReload(sender);
        }

        return true;
    }


    private void handleStatus(CommandSender sender) {
        runAsync(sender, () -> {
            HealthReport report = healthService.generateReport();
            send(sender, Component.text("=== FriendlySync Status ===", NamedTextColor.GOLD));
            send(sender, Component.text("Server ID: " + report.serverId(), NamedTextColor.YELLOW));
            send(sender, Component.text("Active Local Sessions: " + report.activeLocalSessions(), NamedTextColor.YELLOW));
            send(sender, Component.text("Database: " + (report.databaseHealthy() ? "HEALTHY" : "UNHEALTHY"),
                report.databaseHealthy() ? NamedTextColor.GREEN : NamedTextColor.RED));
            send(sender, Component.text("Redis: " + (report.redisHealthy() ? "HEALTHY" : (report.redisEnabled() ? "DISCONNECTED" : "DISABLED")),
                report.redisHealthy() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        });
    }

    private void handleHealth(CommandSender sender) {
        runAsync(sender, () -> {
            HealthReport report = healthService.generateReport();
            send(sender, Component.text("=== FriendlySync Health Diagnostics ===", NamedTextColor.GOLD));
            send(sender, Component.text("Server: " + report.serverId(), NamedTextColor.YELLOW));
            send(sender, Component.text("Database: " + (report.databaseHealthy() ? "HEALTHY" : "UNHEALTHY"),
                report.databaseHealthy() ? NamedTextColor.GREEN : NamedTextColor.RED));
            send(sender, Component.text("DB Active / Idle: " + report.dbActiveConnections() + " / " + report.dbIdleConnections(), NamedTextColor.YELLOW));
            send(sender, Component.text("Waiting for DB connection: " + report.dbThreadsAwaiting(), NamedTextColor.YELLOW));
            send(sender, Component.text("Local Active Sessions: " + report.activeLocalSessions(), NamedTextColor.YELLOW));
            send(sender, Component.text("Queued Operations: " + report.activeQueueCount(), NamedTextColor.YELLOW));
            send(sender, Component.text("Total Saves: " + report.totalSaves() + " | Conflicts: " + report.totalConflicts(), NamedTextColor.YELLOW));
            send(sender, Component.text("Avg SQL Save Latency: " + String.format("%.2f", report.averageSaveLatencyMs()) + " ms", NamedTextColor.YELLOW));
            var traffic = healthService.getPayloadTraffic();
            if (traffic != null) {
                send(sender, Component.text(String.format(java.util.Locale.ROOT,
                    "SQL payload last ~60s: upload %.1f KiB | download %.1f KiB | largest since startup %.1f KiB",
                    traffic.recentUploadBytes() / 1024.0, traffic.recentDownloadBytes() / 1024.0,
                    traffic.largestPayloadBytes() / 1024.0), NamedTextColor.YELLOW));
                send(sender, Component.text("Payload counts exclude protocol overhead, Redis and Minecraft packets; uploads include attempts.", NamedTextColor.GRAY));
            }
            send(sender, Component.text("Redis Connected: " + report.redisHealthy(), report.redisHealthy() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            send(sender, Component.text("Connectivity checks do not confirm player transfers; inspect /friendlysync player <name>.", NamedTextColor.GRAY));
        });
    }

    private void handleServers(CommandSender sender) {
        runAsync(sender, () -> {
            List<ServerInfo> servers = serverPresenceRepository.getActiveServers(Duration.ofSeconds(45));
            send(sender, Component.text("=== FriendlySync Cluster Nodes (" + servers.size() + " online) ===", NamedTextColor.GOLD));
            for (ServerInfo s : servers) {
                send(sender, Component.text(
                    "- " + s.serverId() + " [" + s.platform() + "] (instance=" + s.instanceId() + ", mc=" + s.minecraftVersion() + ", heartbeat=" + s.lastHeartbeat() + ")",
                    NamedTextColor.YELLOW
                ));
            }
        });
    }

    private void handlePlayer(CommandSender sender, String[] args) {
        String targetName = args[1];
        runAsync(sender, () -> {
            UUID uuid = resolvePlayerUuid(sender, targetName);
            if (uuid == null) return;

            send(sender, Component.text("=== Player Audit: " + targetName + " ===", NamedTextColor.GOLD));
            send(sender, Component.text("UUID: " + uuid, NamedTextColor.WHITE));

            Optional<ActivePlayerSession> sessionOpt = sessionService.getLocalSession(uuid);
            if (sessionOpt.isPresent()) {
                ActivePlayerSession s = sessionOpt.get();
                send(sender, Component.text("Session: " + s.getState() + " (" + s.getSessionId() + ")",
                    s.getState() == dev.lyy.friendlysync.model.SessionState.ACTIVE ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                send(sender, Component.text("Revision: " + s.getRevision() + " | Fencing Token: " + s.getFencingToken(), NamedTextColor.YELLOW));
                send(sender, Component.text("Dirty: " + (s.isDirty() ? "YES" : "NO"), s.isDirty() ? NamedTextColor.RED : NamedTextColor.GREEN));
            } else {
                send(sender, Component.text("Session: NONE (Player not active locally)", NamedTextColor.GRAY));
            }

            try {
                Optional<PersistedPlayerState> persistedOpt = playerStateRepository.find(uuid);
                if (persistedOpt.isPresent()) {
                    PersistedPlayerState p = persistedOpt.get();
                    send(sender, Component.text("Target SQL Revision: " + p.revision() + " | State: " + p.sessionState(), NamedTextColor.YELLOW));
                    send(sender, Component.text("Owner Server: " + (p.ownerServer() != null ? p.ownerServer() : "NONE"), NamedTextColor.YELLOW));
                    send(sender, Component.text("Lease Expires: " + p.leaseExpiresAt(), NamedTextColor.YELLOW));

                    if (p.payload() != null) {
                        String calculatedHash = DigestUtils.sha256(p.payload());
                        boolean hashValid = calculatedHash.equalsIgnoreCase(p.payloadHash());
                        send(sender, Component.text("Payload Hash: " + (hashValid ? "VALID" : "CORRUPT"),
                            hashValid ? NamedTextColor.GREEN : NamedTextColor.RED));

                        try {
                            PlayerStateSnapshot snap = serializer.deserialize(p.payload());
                            send(sender, Component.text(snap.inventory() == null ? "Inventory: absent" : "Inventory: OK (" + snap.inventory().storage().length + " slots)", NamedTextColor.GREEN));
                            send(sender, Component.text(snap.enderChest() == null ? "Ender Chest: absent" : "Ender Chest: OK (" + snap.enderChest().items().length + " slots)", NamedTextColor.GREEN));
                            send(sender, Component.text("PDC: OK (" + snap.persistentData().size() + " entries)", NamedTextColor.GREEN));
                            send(sender, Component.text("Statistics: OK (" + snap.statistics().size() + " entries)", NamedTextColor.GREEN));
                        } catch (Exception e) {
                            send(sender, Component.text("Payload Deserialization Error: " + e.getMessage(), NamedTextColor.RED));
                        }
                    }
                } else {
                    send(sender, Component.text("Target SQL State: NO RECORD FOUND", NamedTextColor.GRAY));
                }
            } catch (SQLException e) {
                send(sender, Component.text("Error querying database state: " + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    /** Returns null (after telling the sender) instead of guessing an offline-mode UUID the network never uses. */
    private UUID resolvePlayerUuid(CommandSender sender, String input) {
        try {
            return parseUuid(input);
        } catch (IllegalArgumentException notUuid) {
            try {
                Optional<PersistedPlayerState> byName = playerStateRepository.findByUsername(input);
                if (byName.isPresent()) {
                    return byName.get().uuid();
                }
            } catch (SQLException e) {
                send(sender, Component.text("Username lookup failed: " + e.getMessage() + ". Retry with the player's UUID.", NamedTextColor.RED));
                return null;
            }
            send(sender, Component.text("Unknown player '" + input + "'. Retry with the player's UUID.", NamedTextColor.RED));
            return null;
        }
    }

    private void handleSnapshots(CommandSender sender, String[] args) {
        runAsync(sender, () -> {
            UUID uuid = resolvePlayerUuid(sender, args[1]);
            if (uuid == null) return;
            try {
                var snapRepo = playerStateRepository.getSnapshotRepository();
                if (snapRepo == null) {
                    send(sender, Component.text("Snapshot storage is currently disabled in config.yml.", NamedTextColor.YELLOW));
                    return;
                }
                var snapshots = snapRepo.findRecentSnapshots(uuid, 5);
                if (snapshots.isEmpty()) {
                    send(sender, Component.text("No persistent MySQL snapshots found for player " + args[1], NamedTextColor.YELLOW));
                    return;
                }
                send(sender, Component.text("=== MySQL Persistent Snapshots for " + args[1] + " ===", NamedTextColor.GOLD));
                for (var snap : snapshots) {
                    send(sender, Component.text(String.format("- ID: %s | Rev %d | Token %d | Reason: %s | Status: %s | Server: %s | Time: %s",
                        snap.snapshotId(),
                        snap.revision(), snap.fencingToken(), snap.syncReason(), snap.status(), snap.serverId(), snap.createdAt()),
                        "COMMITTED".equalsIgnoreCase(snap.status()) ? NamedTextColor.GREEN : NamedTextColor.AQUA));
                }
            } catch (Exception e) {
                send(sender, Component.text("Error querying snapshots from MySQL: " + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    private void handleRestore(CommandSender sender, String[] args) {
        String targetInput = args[1];
        String snapshotId = args.length >= 3 ? args[2] : "latest";
        if (!snapshotId.equalsIgnoreCase("latest")) {
            try {
                snapshotId = parseUuid(snapshotId).toString();
            } catch (IllegalArgumentException invalid) {
                send(sender, Component.text("Use a full snapshot UUID from /friendlysync snapshots, or latest.", NamedTextColor.YELLOW));
                return;
            }
        }
        String selectedSnapshot = snapshotId;
        send(sender, Component.text("Restoring snapshot for " + targetInput + "...", NamedTextColor.GRAY));
        runAsync(sender, () -> {
            UUID uuid = resolvePlayerUuid(sender, targetInput);
            if (uuid == null) return;
            stateService.restoreSnapshot(uuid, selectedSnapshot).whenComplete((msg, err) -> {
                if (err != null) {
                    send(sender, Component.text("Error during snapshot restore: " + err.getMessage(), NamedTextColor.RED));
                } else {
                    send(sender, Component.text(msg, NamedTextColor.YELLOW));
                }
            });
        });
    }

    private void handleUnlock(CommandSender sender, String[] args) {
        String targetName = args[1];
        runAsync(sender, () -> {
            UUID uuid = resolvePlayerUuid(sender, targetName);
            if (uuid == null) return;
            if (stateService.hasPendingFinalSave(uuid) || stateService.hasFinalSaveConflict(uuid)) {
                send(sender, Component.text("A final save is still pending synchronization for this player. Please wait a moment before unlocking.", NamedTextColor.RED));
                return;
            }
            if (sessionService.isPlayerOnline(uuid) || sessionService.getLocalSession(uuid).isPresent()) {
                send(sender, Component.text("Cannot unlock " + targetName + ": a local session is still present. Wait for its final save.", NamedTextColor.RED));
                return;
            }

            send(sender, Component.text("Attempting to unlock expired ownership: " + targetName + " (" + uuid + ")...", NamedTextColor.YELLOW));

            try {
                Optional<PersistedPlayerState> persistedOpt = playerStateRepository.find(uuid);
                String prevOwner = persistedOpt.map(p -> p.ownerServer() != null ? p.ownerServer() : "NONE").orElse("NONE");
                String prevState = persistedOpt.map(PersistedPlayerState::sessionState).orElse("NONE");

                boolean updated = playerStateRepository.unlockExpiredOwnership(uuid);
                if (updated) {
                    send(sender, Component.text("Successfully unlocked player " + targetName + " (" + uuid + ")!", NamedTextColor.GREEN));
                    send(sender, Component.text("Previous Owner: " + prevOwner + " | Previous State: " + prevState + " -> Reset to OFFLINE with cleared lease.", NamedTextColor.YELLOW));
                } else {
                    send(sender, Component.text("No expired ownership to unlock for " + targetName + ". A live or renewed lease is protected.", NamedTextColor.YELLOW));
                }
            } catch (SQLException e) {
                send(sender, Component.text("Database error while unlocking player: " + e.getMessage(), NamedTextColor.RED));
            }
        });
    }

    private void handleRecovery(CommandSender sender, String[] args) {
        if (args.length >= 2 && (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("bulk"))) {
            boolean acceptCurrent = args.length == 3 && (args[2].equalsIgnoreCase("accept-current")
                || args[2].equalsIgnoreCase("confirm") || args[2].equalsIgnoreCase("force"));
            handleBulkRecovery(sender, acceptCurrent);
            return;
        }

        if (args.length == 3 && !args[2].equalsIgnoreCase("accept-current")) {
            send(sender, Component.text("Usage: /friendlysync recovery <uuid|name|all> [accept-current]", NamedTextColor.YELLOW));
            return;
        }

        UUID uuid;
        try {
            uuid = parseUuid(args[1]);
        } catch (IllegalArgumentException notUuid) {
            if (isValidMinecraftUsername(args[1])) {
                runAsync(sender, () -> {
                    UUID resolved = resolvePlayerUuid(sender, args[1]);
                    if (resolved == null) return;
                    stateService.reviewRecovery(resolved, args.length == 3).whenComplete((message, error) ->
                        send(sender, Component.text(error == null ? message : "Recovery review failed: " + error.getMessage(),
                            error == null ? NamedTextColor.YELLOW : NamedTextColor.RED)));
                });
                return;
            } else {
                send(sender, Component.text("Usage: /friendlysync recovery <uuid|name|all> [accept-current]", NamedTextColor.YELLOW));
                return;
            }
        }
        stateService.reviewRecovery(uuid, args.length == 3).whenComplete((message, error) ->
            send(sender, Component.text(error == null ? message : "Recovery review failed: " + error.getMessage(),
                error == null ? NamedTextColor.YELLOW : NamedTextColor.RED)));
    }

    private void handleBulkRecovery(CommandSender sender, boolean acceptCurrent) {
        runAsync(sender, () -> {
            List<UUID> conflicts = stateService.getConflictPlayers();
            if (conflicts.isEmpty()) {
                send(sender, Component.text("No final-save conflicts found on this backend.", NamedTextColor.GREEN));
                return;
            }

            if (!acceptCurrent) {
                send(sender, Component.text("=== FriendlySync Conflicted Saves (" + conflicts.size() + " pending) ===", NamedTextColor.GOLD));
                int shown = 0;
                for (UUID u : conflicts) {
                    if (shown++ >= 10) {
                        send(sender, Component.text("... and " + (conflicts.size() - 10) + " more.", NamedTextColor.GRAY));
                        break;
                    }
                    String name = null;
                    try {
                        var full = playerStateRepository.find(u);
                        if (full.isPresent() && full.get().username() != null) {
                            name = full.get().username();
                        }
                    } catch (Exception ignored) {}
                    if (name != null) {
                        send(sender, Component.text("- " + name + " (" + u + ")", NamedTextColor.YELLOW));
                    } else {
                        send(sender, Component.text("- " + u, NamedTextColor.YELLOW));
                    }
                }
                send(sender, Component.text("To resolve all conflicts and allow reconnecting, run:", NamedTextColor.AQUA));
                send(sender, Component.text("  /friendlysync bulkrecovery accept-current", NamedTextColor.GREEN));
                return;
            }

            send(sender, Component.text("Starting bulk recovery for " + conflicts.size() + " player conflict(s)...", NamedTextColor.YELLOW));
            stateService.bulkResolveConflicts(true).whenComplete((result, error) -> {
                if (error != null) {
                    send(sender, Component.text("Bulk recovery failed: " + error.getMessage(), NamedTextColor.RED));
                    return;
                }
                send(sender, Component.text("=== Bulk Recovery Complete ===", NamedTextColor.GOLD));
                send(sender, Component.text("Total conflicts found: " + result.total(), NamedTextColor.YELLOW));
                send(sender, Component.text("Successfully resolved: " + result.resolved(), NamedTextColor.GREEN));
                if (result.failed() > 0) {
                    send(sender, Component.text("Failed to resolve: " + result.failed(), NamedTextColor.RED));
                    for (String msg : result.messages()) {
                        if (msg.contains("Error") || msg.contains("Skipped") || msg.contains("Requires")) {
                            send(sender, Component.text("- " + msg, NamedTextColor.RED));
                        }
                    }
                }
                send(sender, Component.text("Backups preserved in plugins/FriendlySync/pending-saves/resolved/", NamedTextColor.GRAY));
                send(sender, Component.text("Players can now reconnect safely.", NamedTextColor.GREEN));
            });
        });
    }

    private static boolean isValidMinecraftUsername(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }


    private void handleSave(CommandSender sender, String[] args) {
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, Component.text("Player " + args[1] + " is not online locally.", NamedTextColor.RED));
            return;
        }

        String targetName = target.getName();
        send(sender, Component.text("Triggering authoritative save for " + targetName + "...", NamedTextColor.YELLOW));
        stateService.saveState(target.getUniqueId(), SyncReason.MANUAL, true).whenComplete((res, err) -> {
            if (err != null) {
                send(sender, Component.text("Failed to save " + targetName + ": " + err.getMessage(), NamedTextColor.RED));
            } else if (res instanceof CommitResult.Success s) {
                send(sender, Component.text("Saved " + targetName + " at revision " + s.newRevision() + " in " + s.durationMs() + "ms", NamedTextColor.GREEN));
            } else if (res instanceof CommitResult.Conflict c) {
                send(sender, Component.text("Save conflict for " + targetName + ": " + c.reason(), NamedTextColor.RED));
            } else if (res instanceof CommitResult.Failed f) {
                send(sender, Component.text("Save failed for " + targetName + ": " + f.cause().getMessage(), NamedTextColor.RED));
            }
        });
    }

    private void handleReload(CommandSender sender) {
        send(sender, Component.text(
            "FriendlySync configuration is immutable while running. Restart the server to apply changes safely.",
            NamedTextColor.YELLOW));
    }

    public boolean canUse(CommandSender sender) {
        return Arrays.stream(Subcommand.values()).anyMatch(sub -> sub.allowed(sender));
    }

    private static UUID parseUuid(String input) {
        UUID uuid = UUID.fromString(input);
        if (!uuid.toString().equalsIgnoreCase(input)) throw new IllegalArgumentException("Use a full UUID");
        return uuid;
    }

    private void runAsync(CommandSender sender, Runnable action) {
        try {
            scheduler.runAsync(() -> {
                try {
                    action.run();
                } catch (RuntimeException error) {
                    send(sender, Component.text("Command failed: " + error.getMessage(), NamedTextColor.RED));
                }
            });
        } catch (RuntimeException error) {
            send(sender, Component.text("Command could not be scheduled: " + error.getMessage(), NamedTextColor.RED));
        }
    }

    private void send(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            scheduler.runForPlayer(player, () -> player.sendMessage(message));
        } else {
            scheduler.runGlobal(() -> sender.sendMessage(message));
        }
    }

    private void sendHelp(CommandSender sender) {
        send(sender, Component.text("=== FriendlySync Commands ===", NamedTextColor.GOLD));
        for (Subcommand sub : Subcommand.values()) {
            if (sub.allowed(sender)) {
                send(sender, Component.text("/friendlysync " + sub.usage + " - " + sub.description, NamedTextColor.YELLOW));
            }
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, Command command, @NotNull String label, @NotNull String[] args) {
        if (!canUse(sender) || args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> completions = new java.util.ArrayList<>();
            for (Subcommand sub : Subcommand.values()) {
                if (sub.allowed(sender) && sub.commandName().startsWith(prefix)) completions.add(sub.commandName());
            }
            if ("help".startsWith(prefix)) completions.add("help");
            return completions;
        }
        Subcommand sub;
        try {
            sub = Subcommand.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return List.of();
        }
        if (!sub.allowed(sender)) return List.of();
        if (args.length == 2 && switch (sub) {
            case PLAYER, SAVE, UNLOCK, SNAPSHOTS, RESTORE -> true;
            default -> false;
        }) {
            if (Bukkit.getServer() == null) return List.of();
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2 && sub == Subcommand.RECOVERY) {
            List<String> options = new java.util.ArrayList<>();
            if ("all".startsWith(prefix)) options.add("all");
            if (Bukkit.getServer() != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) options.add(p.getName());
                }
            }
            return options;
        }
        if (args.length == 2 && (sub == Subcommand.BULKRECOVERY || sub == Subcommand.RECOVERALL)) {
            List<String> options = new java.util.ArrayList<>();
            if ("accept-current".startsWith(prefix)) options.add("accept-current");
            if ("confirm".startsWith(prefix)) options.add("confirm");
            return options;
        }
        if (args.length == 3 && sub == Subcommand.RECOVERY && "accept-current".startsWith(prefix)) {
            return List.of("accept-current");
        }
        if (args.length == 3 && sub == Subcommand.RESTORE && "latest".startsWith(prefix)) {
            return List.of("latest");
        }
        return List.of();
    }
}
