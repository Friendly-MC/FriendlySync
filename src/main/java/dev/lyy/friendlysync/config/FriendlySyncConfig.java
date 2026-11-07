package dev.lyy.friendlysync.config;

import dev.lyy.friendlysync.database.DatabaseType;
import dev.lyy.friendlysync.serialization.CompressionAlgorithm;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated immutable configuration for FriendlySync.
 */
public final class FriendlySyncConfig {

    private final String serverId;
    private final String shutdownTransferTarget;
    private final boolean shutdownFallbackToDisconnect;

    private final DatabaseType databaseType;
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUsername;
    private final String dbPassword;
    private final int dbPoolMaxSize;
    private final int dbPoolMinIdle;
    private final long dbPoolConnectionTimeout;
    private final long dbPoolValidationTimeout;
    private final long dbPoolMaxLifetime;
    private final long dbPoolLeakDetectionThreshold;

    private final boolean redisEnabled;
    private final String redisHost;
    private final int redisPort;
    private final String redisUsername;
    private final String redisPassword;
    private final int redisDatabase;
    private final boolean redisSsl;
    private final int redisTimeout;

    private final Duration sessionHeartbeatInterval;
    private final Duration sessionLeaseTimeout;

    // Sync
    private final Duration checkpointInterval;
    private final Duration saveCoalesceDelay;
    private final Map<String, Boolean> enabledModules;
    private final dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy pdcPolicy;
    private final boolean statisticsEnabled;
    private final boolean statisticsCaptureOnCheckpoint;
    private final boolean statisticsCaptureOnFinalSave;

    private final boolean compressionEnabled;
    private final CompressionAlgorithm compressionAlgorithm;
    private final int compressionMinimumSizeBytes;

    private final Duration transferTimeout;
    private final boolean transferFreezePlayer;
    private final boolean transferRejectOnSaveFailure;

    private final String staleSessionMode;
    private final Duration recoveryMaximumWait;

    private final boolean auditEnabled;
    private final int auditRetentionDays;

    private final boolean snapshotsEnabled;
    private final int snapshotsRetentionDays;
    private final boolean snapshotsContinuousInserts;

    public FriendlySyncConfig(FileConfiguration config) {
        Preconditions.checkNotNull(config, "config cannot be null");

        this.serverId = validateNotEmpty(config.getString("server.id"), "server.id cannot be empty");
        if ("change-me".equalsIgnoreCase(serverId)) {
            throw new ConfigValidationException("server.id must be changed to a unique backend identifier");
        }
        this.shutdownTransferTarget = config.getString("shutdown.transfer-target", "").strip();
        if (serverId.equalsIgnoreCase(shutdownTransferTarget)) {
            throw new ConfigValidationException("shutdown.transfer-target must be a different backend from server.id");
        }
        this.shutdownFallbackToDisconnect = config.getBoolean("shutdown.fallback-to-disconnect", true);

        String dbTypeStr = config.getString("database.type", "mysql").toUpperCase();
        try {
            this.databaseType = DatabaseType.valueOf(dbTypeStr);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException("Unsupported database.type: " + dbTypeStr + ". Supported: MYSQL, MARIADB");
        }
        this.dbHost = validateNotEmpty(config.getString("database.host", "127.0.0.1"), "database.host cannot be empty");
        this.dbPort = config.getInt("database.port", 3306);
        this.dbName = validateNotEmpty(config.getString("database.database", "friendlysync"), "database.database cannot be empty");
        this.dbUsername = validateNotEmpty(config.getString("database.username", "friendlysync"), "database.username cannot be empty");

        String envDbPass = System.getenv("FRIENDLYSYNC_DB_PASSWORD");
        this.dbPassword = envDbPass != null ? envDbPass : config.getString("database.password", "change-me");
        if ("change-me".equals(dbPassword)) {
            throw new ConfigValidationException(
                "database.password must be configured or supplied through FRIENDLYSYNC_DB_PASSWORD"
            );
        }

        this.dbPoolMaxSize = Math.max(1, config.getInt("database.pool.maximum-size", 20));
        this.dbPoolMinIdle = Math.min(dbPoolMaxSize, Math.max(0, config.getInt("database.pool.minimum-idle", 2)));
        this.dbPoolConnectionTimeout = config.getLong("database.pool.connection-timeout", 15000);
        this.dbPoolValidationTimeout = config.getLong("database.pool.validation-timeout", 3000);
        this.dbPoolMaxLifetime = config.getLong("database.pool.max-lifetime", 1800000);
        this.dbPoolLeakDetectionThreshold = config.getLong("database.pool.leak-detection-threshold", 30000);

        this.redisEnabled = config.getBoolean("redis.enabled", true);
        this.redisHost = config.getString("redis.host", "127.0.0.1");
        this.redisPort = config.getInt("redis.port", 6379);
        this.redisUsername = config.getString("redis.username", "");

        String envRedisPass = System.getenv("FRIENDLYSYNC_REDIS_PASSWORD");
        this.redisPassword = envRedisPass != null ? envRedisPass : config.getString("redis.password", "");

        this.redisDatabase = config.getInt("redis.database", 0);
        this.redisSsl = config.getBoolean("redis.ssl", false);
        this.redisTimeout = config.getInt("redis.timeout", 3000);

        this.sessionHeartbeatInterval = parseDuration(config.getString("sessions.heartbeat-interval", "30s"), Duration.ofSeconds(30));
        this.sessionLeaseTimeout = parseDuration(config.getString("sessions.lease-timeout", "45s"), Duration.ofSeconds(45));
        if (sessionLeaseTimeout.compareTo(sessionHeartbeatInterval) <= 0) {
            throw new ConfigValidationException("sessions.lease-timeout must be strictly greater than sessions.heartbeat-interval");
        }

        // Sync
        this.checkpointInterval = parseDuration(config.getString("sync.checkpoint-interval", "30s"), Duration.ofSeconds(30));
        if (checkpointInterval.compareTo(Duration.ofSeconds(1)) < 0
            || checkpointInterval.compareTo(Duration.ofDays(1)) > 0) {
            throw new ConfigValidationException("sync.checkpoint-interval must be between 1 second and 24 hours");
        }
        this.saveCoalesceDelay = parseDuration(config.getString("sync.save-coalesce-delay", "2s"), Duration.ofSeconds(2));

        Map<String, Boolean> modules = new HashMap<>();
        if (config.isConfigurationSection("sync.modules")) {
            for (String key : config.getConfigurationSection("sync.modules").getKeys(false)) {
                modules.put(key, config.getBoolean("sync.modules." + key));
            }
        }
        this.enabledModules = Collections.unmodifiableMap(modules);

        boolean pdcEnabled = config.getBoolean("sync.pdc.enabled", config.getBoolean("sync.modules.persistent_data", true));
        String pdcMode = config.getString("sync.pdc.mode", "ALL_SAFE_KEYS");
        List<String> pdcManaged = config.getStringList("sync.pdc.managed-namespaces");
        List<String> pdcExcluded = config.getStringList("sync.pdc.excluded-namespaces");
        int pdcMaxTotal = config.getInt("sync.pdc.maximum-total-bytes", 1_048_576);
        int pdcMaxEntry = config.getInt("sync.pdc.maximum-entry-bytes", 262_144);
        int pdcMaxDepth = config.getInt("sync.pdc.maximum-depth", 16);
        this.pdcPolicy = new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
            pdcEnabled, pdcMode, pdcManaged, pdcExcluded, pdcMaxTotal, pdcMaxEntry, pdcMaxDepth
        );

        this.statisticsEnabled = config.getBoolean("sync.statistics.enabled", config.getBoolean("sync.modules.statistics", false));
        this.statisticsCaptureOnCheckpoint = config.getBoolean("sync.statistics.capture-on-checkpoint", false);
        this.statisticsCaptureOnFinalSave = config.getBoolean("sync.statistics.capture-on-final-save", true);
        if (statisticsEnabled && !statisticsCaptureOnFinalSave) {
            throw new ConfigValidationException(
                "statistics capture-on-final-save must remain true because transfer and shutdown snapshots are authoritative"
            );
        }

        this.compressionEnabled = config.getBoolean("storage.compression.enabled", true);
        String compAlgoStr = config.getString("storage.compression.algorithm", "zstd").toUpperCase();
        try {
            this.compressionAlgorithm = CompressionAlgorithm.valueOf(compAlgoStr);
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException("Unsupported compression algorithm: " + compAlgoStr + ". Supported: NONE, GZIP, ZSTD");
        }
        this.compressionMinimumSizeBytes = Math.max(0, config.getInt("storage.compression.minimum-size-bytes", 2048));

        this.transferTimeout = parseDuration(config.getString("transfer.timeout", "10s"), Duration.ofSeconds(10));
        if (transferTimeout.isZero() || transferTimeout.isNegative()) {
            throw new ConfigValidationException("transfer.timeout must be positive");
        }
        this.transferFreezePlayer = config.getBoolean("transfer.freeze-player", true);
        this.transferRejectOnSaveFailure = config.getBoolean("transfer.reject-on-save-failure", true);
        if (!transferFreezePlayer || !transferRejectOnSaveFailure) {
            throw new ConfigValidationException(
                "transfer.freeze-player and transfer.reject-on-save-failure must remain true to preserve player-state integrity"
            );
        }

        this.staleSessionMode = config.getString("recovery.stale-session-mode", "safe");
        this.recoveryMaximumWait = parseDuration(config.getString("recovery.maximum-wait", "45s"), Duration.ofSeconds(45));
        if (!"safe".equalsIgnoreCase(staleSessionMode)) {
            throw new ConfigValidationException("recovery.stale-session-mode currently supports only 'safe'");
        }

        this.auditEnabled = config.getBoolean("audit.enabled", true);
        this.auditRetentionDays = Math.max(1, config.getInt("audit.retention-days", 7));

        this.snapshotsEnabled = config.getBoolean("snapshots.enabled", true);
        this.snapshotsRetentionDays = Math.max(1, config.getInt("snapshots.retention-days", 7));
        this.snapshotsContinuousInserts = config.getBoolean("snapshots.continuous-inserts", false);
    }

    private static String validateNotEmpty(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ConfigValidationException(message);
        }
        return value.trim();
    }

    private static Duration parseDuration(String input, Duration fallback) {
        if (input == null || input.trim().isEmpty()) {
            return fallback;
        }
        input = input.trim().toLowerCase();
        try {
            if (input.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(input.substring(0, input.length() - 2)));
            } else if (input.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(input.substring(0, input.length() - 1)));
            } else if (input.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(input.substring(0, input.length() - 1)));
            } else {
                return Duration.ofSeconds(Long.parseLong(input));
            }
        } catch (NumberFormatException e) {
            throw new ConfigValidationException("Invalid duration string: " + input, e);
        }
    }

    public String getServerId() {
        return serverId;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public String getDbHost() {
        return dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    public String getDbName() {
        return dbName;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public int getDbPoolMaxSize() {
        return dbPoolMaxSize;
    }

    public int getDbPoolMinIdle() {
        return dbPoolMinIdle;
    }

    public long getDbPoolConnectionTimeout() {
        return dbPoolConnectionTimeout;
    }

    public long getDbPoolValidationTimeout() {
        return dbPoolValidationTimeout;
    }

    public long getDbPoolMaxLifetime() {
        return dbPoolMaxLifetime;
    }

    public long getDbPoolLeakDetectionThreshold() {
        return dbPoolLeakDetectionThreshold;
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisUsername() {
        return redisUsername;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public int getRedisDatabase() {
        return redisDatabase;
    }

    public boolean isRedisSsl() {
        return redisSsl;
    }

    public int getRedisTimeout() {
        return redisTimeout;
    }

    public Duration getSessionHeartbeatInterval() {
        return sessionHeartbeatInterval;
    }

    public Duration getSessionLeaseTimeout() {
        return sessionLeaseTimeout;
    }

    public Duration getCheckpointInterval() {
        return checkpointInterval;
    }

    public Duration getSaveCoalesceDelay() {
        return saveCoalesceDelay;
    }

    public boolean isModuleEnabled(String moduleId) {
        return enabledModules.getOrDefault(moduleId, true);
    }

    public Map<String, Boolean> getEnabledModules() {
        return enabledModules;
    }

    public boolean isPdcEnabled() {
        return pdcPolicy.isEnabled() && isModuleEnabled("persistent_data");
    }

    public dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy getPdcPolicy() {
        return pdcPolicy;
    }

    public boolean isStatisticsEnabled() {
        return statisticsEnabled && isModuleEnabled("statistics");
    }

    public boolean isStatisticsCaptureOnCheckpoint() {
        return statisticsCaptureOnCheckpoint;
    }

    public boolean isStatisticsCaptureOnFinalSave() {
        return statisticsCaptureOnFinalSave;
    }

    public boolean isCompressionEnabled() {
        return compressionEnabled;
    }

    public CompressionAlgorithm getCompressionAlgorithm() {
        return compressionAlgorithm;
    }

    public int getCompressionMinimumSizeBytes() {
        return compressionMinimumSizeBytes;
    }

    public Duration getTransferTimeout() {
        return transferTimeout;
    }

    public String getShutdownTransferTarget() {
        return shutdownTransferTarget;
    }

    public boolean isShutdownFallbackToDisconnect() {
        return shutdownFallbackToDisconnect;
    }

    public boolean isTransferFreezePlayer() {
        return transferFreezePlayer;
    }

    public boolean isTransferRejectOnSaveFailure() {
        return transferRejectOnSaveFailure;
    }

    public String getStaleSessionMode() {
        return staleSessionMode;
    }

    public Duration getRecoveryMaximumWait() {
        return recoveryMaximumWait;
    }

    public boolean isMetricsEnabled() {
        return true;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public int getAuditRetentionDays() {
        return auditRetentionDays;
    }

    public boolean isSnapshotsEnabled() {
        return snapshotsEnabled;
    }

    public int getSnapshotsRetentionDays() {
        return snapshotsRetentionDays;
    }

    public boolean isSnapshotsContinuousInserts() {
        return snapshotsContinuousInserts;
    }
}
