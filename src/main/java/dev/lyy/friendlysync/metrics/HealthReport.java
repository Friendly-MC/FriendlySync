package dev.lyy.friendlysync.metrics;

import java.util.UUID;

/**
 * Snapshot report of cluster and subsystem health status.
 */
public record HealthReport(
    String serverId,
    UUID instanceId,
    boolean databaseHealthy,
    int dbActiveConnections,
    int dbIdleConnections,
    int dbThreadsAwaiting,
    boolean redisHealthy,
    boolean redisEnabled,
    int activeLocalSessions,
    int activeQueueCount,
    double averageSaveLatencyMs,
    long totalSaves,
    long totalConflicts
) {
}
