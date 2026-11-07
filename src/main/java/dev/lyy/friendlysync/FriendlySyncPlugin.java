package dev.lyy.friendlysync;

import dev.lyy.friendlysync.api.FriendlySync;
import dev.lyy.friendlysync.command.FriendlySyncCommand;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.migration.DatabaseMigrator;
import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.database.repository.ServerPresenceRepository;
import dev.lyy.friendlysync.database.repository.SessionRepository;
import dev.lyy.friendlysync.database.repository.TransferLogRepository;
import dev.lyy.friendlysync.listener.PlayerConnectionListener;
import dev.lyy.friendlysync.listener.PlayerDeathListener;
import dev.lyy.friendlysync.listener.PlayerInventoryListener;
import dev.lyy.friendlysync.database.repository.PlayerSnapshotRepository;
import dev.lyy.friendlysync.lock.JoinLockService;
import dev.lyy.friendlysync.lock.TransferFreezeService;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.messaging.RedisMessagingService;
import dev.lyy.friendlysync.metrics.HealthService;
import dev.lyy.friendlysync.metrics.MetricsService;
import dev.lyy.friendlysync.scheduler.FoliaScheduler;
import dev.lyy.friendlysync.scheduler.PaperScheduler;
import dev.lyy.friendlysync.scheduler.PlatformScheduler;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.scheduler.ScheduledTaskHandle;
import dev.lyy.friendlysync.serialization.CompressionService;
import dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.service.FriendlySyncApiImpl;
import dev.lyy.friendlysync.session.PlayerOwnershipService;
import dev.lyy.friendlysync.session.PlayerSessionCoordinator;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.session.PlayerStateService;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.server.ServerPlatform;
import dev.lyy.friendlysync.transfer.PlayerTransferService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FriendlySync Main Plugin Bootstrap.
 * Enterprise-grade cross-server player data synchronization for Paper and Folia.
 */
public final class FriendlySyncPlugin extends JavaPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(FriendlySyncPlugin.class);

    private UUID instanceId;
    private FriendlySyncConfig config;
    private PlatformScheduler scheduler;
    private DatabaseManager databaseManager;
    private MessagingService messagingService;
    private ExecutorService workerExecutor;
    private PlayerOperationQueue operationQueue;

    private PlayerSessionService sessionService;
    private PlayerOwnershipService ownershipService;
    private PlayerStateService stateService;
    private PlayerTransferService transferService;
    private PlayerSessionCoordinator sessionCoordinator;
    private SyncModuleManager syncModuleManager;
    private JoinLockService joinLockService;
    private TransferFreezeService freezeService;
    private DirtyTrackingService dirtyTrackingService;
    private HealthService healthService;
    private MetricsService metricsService;
    private Metrics bStats;

    private PlayerStateRepository playerStateRepository;
    private SessionRepository sessionRepository;
    private TransferLogRepository transferLogRepository;
    private ServerPresenceRepository serverPresenceRepository;

    private final List<ScheduledTaskHandle> scheduledTasks = new ArrayList<>();

    static ExecutorService createWorkerExecutor(int threads, int capacity, java.util.concurrent.ThreadFactory factory,
                                                java.util.function.BooleanSupplier onTickThread) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity), factory, (task, executor) -> {
                if (executor.isShutdown() || onTickThread.getAsBoolean()) {
                    throw new java.util.concurrent.RejectedExecutionException("FriendlySync worker pool is unavailable");
                }
                // Apply backpressure to background producers without losing completion
                // handlers that release ownership. Caller execution never touches ticks.
                task.run();
            });
    }

    @Override
    public void onEnable() {
        this.instanceId = UUID.randomUUID();

        dev.lyy.friendlysync.util.ClassPreloader.preload(getClassLoader(), getClass());

        saveDefaultConfig();
        try (var defaults = new java.io.InputStreamReader(getResource("config.yml"), java.nio.charset.StandardCharsets.UTF_8)) {
            var changes = dev.lyy.friendlysync.config.ConfigUpdater.update(
                getDataFolder().toPath().resolve("config.yml"), org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(defaults));
            if (!changes.isEmpty()) LOGGER.info("Updated config.yml: {}", String.join(", ", changes));
        } catch (Exception e) {
            // The update is optional; startup continues with the file as the admin left it.
            LOGGER.warn("Could not update config.yml; using it unchanged: {}", e.getMessage());
        }
        try {
            this.config = new FriendlySyncConfig(getConfig());
        } catch (Exception e) {
            LOGGER.error("Fatal configuration error: {}", e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ServerPlatform platform = ServerPlatform.detect();
        this.scheduler = platform.isRegionized() ? new FoliaScheduler(this) : new PaperScheduler(this);
        LOGGER.info("FriendlySync initialized platform scheduler for {} (Regionized Multithreading: {})",
            platform.getDisplayName(), platform.isRegionized());

        this.databaseManager = new DatabaseManager(config);
        try {
            databaseManager.initialize();
            DatabaseMigrator migrator = new DatabaseMigrator(databaseManager);
            migrator.migrate();
        } catch (Exception e) {
            LOGGER.error("Fatal database initialization error: {}", e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PlayerSnapshotRepository snapshotRepository = config.isSnapshotsEnabled()
            ? new PlayerSnapshotRepository(databaseManager)
            : null;
        this.playerStateRepository = new PlayerStateRepository(databaseManager, snapshotRepository);
        this.playerStateRepository.setContinuousSnapshotsEnabled(config.isSnapshotsContinuousInserts());
        this.sessionRepository = new SessionRepository(databaseManager);
        this.transferLogRepository = new TransferLogRepository(databaseManager);
        this.serverPresenceRepository = new ServerPresenceRepository(databaseManager);

        boolean registeredPresence = serverPresenceRepository.registerServer(
            config.getServerId(),
            instanceId,
            getPluginMeta().getVersion(),
            getServer().getMinecraftVersion(),
            platform.getDisplayName(),
            Duration.ofSeconds(45),
            true
        );
        if (!registeredPresence) {
            LOGGER.error("Server ID '{}' is already active or could not be registered; disabling to protect session ownership",
                config.getServerId());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ClassLoader pluginClassLoader = getClassLoader();
        AtomicInteger workerThreadId = new AtomicInteger(1);
        int workerThreads = Math.min(
            config.getDbPoolMaxSize(),
            Math.max(4, Runtime.getRuntime().availableProcessors())
        );
        this.workerExecutor = createWorkerExecutor(workerThreads, 2048,
            r -> {
                Thread t = new Thread(r, "FriendlySync-Worker-" + workerThreadId.getAndIncrement());
                t.setDaemon(true);
                t.setContextClassLoader(pluginClassLoader);
                return t;
            },
            org.bukkit.Bukkit::isPrimaryThread
        );
        this.operationQueue = new PlayerOperationQueue(workerExecutor);

        this.messagingService = new RedisMessagingService(config);
        messagingService.initialize();

        CompressionService compressionService = new CompressionService(
            config.isCompressionEnabled() ? config.getCompressionAlgorithm() : dev.lyy.friendlysync.serialization.CompressionAlgorithm.NONE,
            config.getCompressionMinimumSizeBytes()
        );
        PlayerStateSerializer serializer = new DefaultPlayerStateSerializer(compressionService);
        this.syncModuleManager = new SyncModuleManager(config);

        this.sessionService = new PlayerSessionService();
        this.syncModuleManager.setSessionService(sessionService);
        this.joinLockService = new JoinLockService(sessionService);
        this.freezeService = new TransferFreezeService(sessionService, joinLockService);
        this.metricsService = config.isMetricsEnabled() ? new MetricsService() : null;
        this.ownershipService = new PlayerOwnershipService(config, playerStateRepository, sessionRepository, sessionService, metricsService);
        this.ownershipService.setOnSessionFenced(uuid -> {
            org.bukkit.entity.Player fenced = getServer().getPlayer(uuid);
            if (fenced != null) {
                scheduler.runForPlayer(fenced, () -> {
                    if (fenced.isOnline()) {
                        fenced.kick(net.kyori.adventure.text.Component.text(
                            "Your session continued on another server. Please reconnect."));
                    }
                });
            }
        });

        this.stateService = new PlayerStateService(
            playerStateRepository,
            serializer,
            operationQueue,
            syncModuleManager,
            sessionService,
            scheduler,
            workerExecutor,
            metricsService,
            messagingService,
            config
        );

        // Recover pending final saves before reclaiming orphaned sessions to prevent clearing owner_server
        try {
            stateService.enableFinalSaveJournal(getDataFolder().toPath().resolve("pending-saves"), config.getServerId());
            stateService.recoverPendingFinalSaves().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.error("Cannot initialize final-save recovery; disabling to protect player data", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            int reclaimed = playerStateRepository.reclaimOrphanedServerSessions(
                config.getServerId(), stateService.unresolvedFinalSavePlayers());
            if (reclaimed > 0) {
                LOGGER.warn("Reclaimed {} orphaned player session(s) previously owned by '{}' (unclean shutdown; restored from last saved data)",
                    reclaimed, config.getServerId());
            }
        } catch (SQLException | java.io.IOException e) {
            LOGGER.warn("Failed to reclaim orphaned sessions for '{}': {}", config.getServerId(), e.getMessage());
        }

        scheduledTasks.add(scheduler.runAsyncRepeating(Duration.ofSeconds(5), Duration.ofSeconds(5),
            () -> stateService.recoverPendingFinalSaves().exceptionally(error -> {
                LOGGER.error("Final-save recovery worker failed", error);
                return null;
            })));

        if (config.isSnapshotsEnabled()) {
            scheduledTasks.add(scheduler.runAsyncRepeating(Duration.ofHours(2), Duration.ofHours(2), () -> {
                try {
                    int pruned = playerStateRepository.getSnapshotRepository().pruneOldSnapshots(
                        Duration.ofDays(config.getSnapshotsRetentionDays())
                    );
                    if (pruned > 0) {
                        LOGGER.info("Pruned {} expired player snapshot(s) from MySQL", pruned);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to prune old player snapshots from MySQL: {}", e.getMessage());
                }
            }));
        }

        this.dirtyTrackingService = new DirtyTrackingService(
            sessionService,
            uuid -> stateService.saveState(uuid, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT, false),
            scheduler,
            config.getSaveCoalesceDelay()
        );

        this.transferService = new PlayerTransferService(
            this,
            config,
            scheduler,
            operationQueue,
            sessionService,
            ownershipService,
            syncModuleManager,
            stateService,
            transferLogRepository,
            messagingService,
            freezeService,
            joinLockService
        );

        this.sessionCoordinator = new PlayerSessionCoordinator(
            ownershipService,
            sessionService,
            stateService,
            syncModuleManager,
            joinLockService,
            scheduler,
            dirtyTrackingService,
            messagingService,
            freezeService,
            workerExecutor
        );
        this.sessionCoordinator.configureOutgoingTransfers(transferService);

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "friendlycore:rtp_return");

        FriendlySyncApiImpl apiImpl = new FriendlySyncApiImpl(
            sessionService,
            stateService,
            playerStateRepository,
            syncModuleManager,
            dirtyTrackingService,
            serializer,
            joinLockService
        );
        FriendlySync.setApi(apiImpl);

        this.healthService = new HealthService(
            config,
            instanceId,
            databaseManager,
            messagingService,
            sessionService,
            operationQueue,
            metricsService
        );

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(sessionCoordinator), this);
        getServer().getPluginManager().registerEvents(new PlayerInventoryListener(joinLockService, dirtyTrackingService), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(dirtyTrackingService, scheduler, sessionService), this);

        var shutdownListener = new dev.lyy.friendlysync.listener.GracefulShutdownListener(
            sessionCoordinator, scheduler, config, transferService);
        getServer().getPluginManager().registerEvents(shutdownListener, this);

        FriendlySyncCommand cmd = new FriendlySyncCommand(
            sessionService,
            stateService,
            healthService,
            serverPresenceRepository,
            scheduler,
            playerStateRepository,
            serializer
        );
        try {
            this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> {
                    io.papermc.paper.command.brigadier.Commands commands = event.registrar();
                    commands.register(
                        "friendlysync",
                        "FriendlySync administrative commands",
                        List.of("fsync"),
                        new io.papermc.paper.command.brigadier.BasicCommand() {
                            @Override
                            public void execute(io.papermc.paper.command.brigadier.CommandSourceStack stack, String[] args) {
                                cmd.onCommand(stack.getSender(), null, "friendlysync", args);
                            }

                            @Override
                            public java.util.Collection<String> suggest(io.papermc.paper.command.brigadier.CommandSourceStack stack, String[] args) {
                                List<String> completions = cmd.onTabComplete(stack.getSender(), null, "friendlysync", args);
                                return completions != null ? completions : List.of();
                            }

                            @Override
                            public boolean canUse(org.bukkit.command.CommandSender sender) {
                                return cmd.canUse(sender);
                            }
                        }
                    );
                }
            );
            LOGGER.info("Registered FriendlySync command via Paper LifecycleEvents.");
        } catch (Throwable t) {
            // Fallback for legacy Bukkit/Spigot runtime without LifecycleEventManager
            try {
                PluginCommand fsyncCmd = getCommand("friendlysync");
                if (fsyncCmd != null) {
                    fsyncCmd.setExecutor(cmd);
                    fsyncCmd.setTabCompleter(cmd);
                    LOGGER.info("Registered FriendlySync command via legacy Bukkit PluginCommand.");
                }
            } catch (Throwable ignored) {
            }
        }

        // Schedule Recurring Tasks
        // 1. Lease renewals (heartbeat) & orphaned pre-login handshake cleanup
        scheduledTasks.add(scheduler.runAsyncRepeating(
            config.getSessionHeartbeatInterval(),
            config.getSessionHeartbeatInterval(),
            () -> {
                ownershipService.renewActiveLeases();
                sessionCoordinator.pruneStalePendingSnapshots();
            }
        ));

        // 2. Dirty checkpoints, distributed over the interval to avoid a server-wide upload burst.
        scheduledTasks.add(scheduler.runAsyncRepeating(
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            () -> dirtyTrackingService.runCheckpointSlice(config.getCheckpointInterval())
        ));

        scheduledTasks.add(scheduler.runAsyncRepeating(
            Duration.ofSeconds(15),
            Duration.ofSeconds(15),
            () -> {
                serverPresenceRepository.updateHeartbeat(config.getServerId(), instanceId);
                if (messagingService != null && config.isRedisEnabled()) {
                    try {
                        messagingService.publishServerHeartbeat(
                            dev.lyy.friendlysync.messaging.message.ServerHeartbeatMessage.create(config.getServerId(), instanceId)
                        );
                    } catch (Exception ignored) {
                    }
                }
            }
        ));

        if (config.isAuditEnabled()) {
            scheduledTasks.add(scheduler.runAsyncRepeating(
                Duration.ofHours(1),
                Duration.ofHours(6),
                () -> {
                    transferLogRepository.cleanupOldLogs(config.getAuditRetentionDays());
                    try {
                        int prunedSessions = sessionRepository.pruneOldSessions(Duration.ofDays(config.getAuditRetentionDays()));
                        if (prunedSessions > 0) {
                            LOGGER.info("Pruned {} expired session history record(s) from MySQL", prunedSessions);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to prune old session history: {}", e.getMessage());
                    }
                }
            ));
        }

        try {
            this.bStats = new Metrics(this, 33924);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not initialize bStats; synchronization will continue", e);
        }

        LOGGER.info("FriendlySync v{} enabled successfully on server '{}' (instance {})",
            getPluginMeta().getVersion(), config.getServerId(), instanceId);
    }

    @Override
    public void onDisable() {
        LOGGER.info("FriendlySync shutting down...");

        if (bStats != null) {
            bStats.shutdown();
            bStats = null;
        }

        for (ScheduledTaskHandle task : scheduledTasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
        scheduledTasks.clear();

        if (sessionCoordinator != null) {
            sessionCoordinator.handleServerShutdown();
        }

        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (messagingService != null) {
            messagingService.close();
        }

        // Remove only this process's presence row so a clean restart can immediately
        // reuse the same configured server ID. Crashes still rely on lease expiry.
        if (serverPresenceRepository != null && config != null && instanceId != null) {
            serverPresenceRepository.unregisterServer(config.getServerId(), instanceId);
        }

        if (databaseManager != null) {
            databaseManager.close();
        }

        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, "friendlycore:rtp_return");
        } catch (Exception ignored) {
        }

        // Clear static API instance to avoid classloader leaks
        FriendlySync.setApi(null);

        LOGGER.info("FriendlySync disabled cleanly.");
    }

    public FriendlySyncConfig getFriendlySyncConfig() {
        return config;
    }

    public PlatformScheduler getPlatformScheduler() {
        return scheduler;
    }

    public PlayerTransferService getTransferService() {
        return transferService;
    }

    public JoinLockService getJoinLockService() {
        return joinLockService;
    }
}
