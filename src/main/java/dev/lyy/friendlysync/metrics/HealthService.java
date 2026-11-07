package dev.lyy.friendlysync.metrics;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.messaging.MessagingService;
import dev.lyy.friendlysync.scheduler.PlayerOperationQueue;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.util.Preconditions;

import java.util.UUID;

/**
 * Aggregates health diagnostic information for monitoring and admin commands.
 */
public final class HealthService {

    private final FriendlySyncConfig config;
    private final UUID instanceId;
    private final DatabaseManager databaseManager;
    private final MessagingService messagingService;
    private final PlayerSessionService sessionService;
    private final PlayerOperationQueue operationQueue;
    private final MetricsService metricsService;

    public HealthService(
        FriendlySyncConfig config,
        UUID instanceId,
        DatabaseManager databaseManager,
        MessagingService messagingService,
        PlayerSessionService sessionService,
        PlayerOperationQueue operationQueue,
        MetricsService metricsService
    ) {
        this.config = Preconditions.checkNotNull(config, "config cannot be null");
        this.instanceId = Preconditions.checkNotNull(instanceId, "instanceId cannot be null");
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
        this.messagingService = Preconditions.checkNotNull(messagingService, "messagingService cannot be null");
        this.sessionService = Preconditions.checkNotNull(sessionService, "sessionService cannot be null");
        this.operationQueue = Preconditions.checkNotNull(operationQueue, "operationQueue cannot be null");
        this.metricsService = metricsService;
    }

    public HealthReport generateReport() {
        return new HealthReport(
            config.getServerId(),
            instanceId,
            databaseManager.isHealthy(),
            databaseManager.getActiveConnections(),
            databaseManager.getIdleConnections(),
            databaseManager.getThreadsAwaitingConnection(),
            messagingService.isHealthy(),
            config.isRedisEnabled(),
            sessionService.getActiveSessionCount(),
            operationQueue.getActiveQueueCount(),
            metricsService != null ? metricsService.getAverageSaveLatencyMs() : 0.0,
            metricsService != null ? metricsService.getTotalSaves() : 0,
            metricsService != null ? metricsService.getTotalConflicts() : 0
        );
    }

    public PayloadTraffic.Snapshot getPayloadTraffic() {
        return databaseManager.getPayloadTraffic();
    }
}
