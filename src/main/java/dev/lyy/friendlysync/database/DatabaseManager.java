package dev.lyy.friendlysync.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Manages the HikariCP JDBC connection pool lifecycle.
 */
public final class DatabaseManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);

    private final FriendlySyncConfig config;
    private volatile HikariDataSource dataSource;
    private final dev.lyy.friendlysync.metrics.PayloadTraffic payloadTraffic = new dev.lyy.friendlysync.metrics.PayloadTraffic();

    public void recordPayloadUpload(int bytes) {
        payloadTraffic.recordUpload(bytes);
    }

    public void recordPayloadDownload(int bytes) {
        payloadTraffic.recordDownload(bytes);
    }

    public dev.lyy.friendlysync.metrics.PayloadTraffic.Snapshot getPayloadTraffic() {
        return payloadTraffic.snapshot();
    }

    public DatabaseManager(FriendlySyncConfig config) {
        this.config = Preconditions.checkNotNull(config, "config cannot be null");
    }

    public synchronized void initialize() {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        LOGGER.info("Initializing database connection pool for {}", config.getDatabaseType());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("FriendlySync-Pool");
        hikariConfig.setDriverClassName(config.getDatabaseType().getDriverClassName());

        String timezoneParams;
        if (config.getDatabaseType() == DatabaseType.MYSQL) {
            timezoneParams = "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        } else if (config.getDatabaseType() == DatabaseType.MARIADB) {
            timezoneParams = "&sessionVariables=time_zone='%2B00:00'";
        } else {
            timezoneParams = "";
        }

        String jdbcUrl = String.format(
            "%s%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&rewriteBatchedStatements=true&allowMultiQueries=false&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true&connectTimeout=5000&socketTimeout=15000&tcpKeepAlive=true%s",
            config.getDatabaseType().getJdbcPrefix(),
            config.getDbHost(),
            config.getDbPort(),
            config.getDbName(),
            timezoneParams
        );

        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.getDbUsername());
        hikariConfig.setPassword(config.getDbPassword());
        // Bound row-lock and metadata-lock stalls on plugin connections; transaction callers roll back on failure.
        hikariConfig.setConnectionInitSql("SET time_zone = '+00:00', innodb_lock_wait_timeout = 10, lock_wait_timeout = 15");

        hikariConfig.setMaximumPoolSize(config.getDbPoolMaxSize());
        hikariConfig.setMinimumIdle(config.getDbPoolMinIdle());
        hikariConfig.setConnectionTimeout(config.getDbPoolConnectionTimeout());
        hikariConfig.setValidationTimeout(config.getDbPoolValidationTimeout());
        hikariConfig.setMaxLifetime(Math.min(config.getDbPoolMaxLifetime(), 600000L));
        hikariConfig.setKeepaliveTime(30000); // Actively ping idle connections to prevent MySQL wait_timeout and firewall drops
        if (config.getDbPoolLeakDetectionThreshold() > 0) {
            hikariConfig.setLeakDetectionThreshold(config.getDbPoolLeakDetectionThreshold());
        }

        this.dataSource = new HikariDataSource(hikariConfig);

        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(3)) {
                throw new SQLException("Initial database connection validation failed");
            }
            LOGGER.info("Successfully connected to database pool (active={}, idle={})",
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections());
        } catch (SQLException e) {
            close();
            throw new IllegalStateException("Failed to establish initial database connection: " + e.getMessage(), e);
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database connection pool is closed or not initialized");
        }
        return dataSource.getConnection();
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public boolean isHealthy() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    public int getActiveConnections() {
        return (dataSource != null && !dataSource.isClosed())
            ? dataSource.getHikariPoolMXBean().getActiveConnections()
            : 0;
    }

    public int getIdleConnections() {
        return (dataSource != null && !dataSource.isClosed())
            ? dataSource.getHikariPoolMXBean().getIdleConnections()
            : 0;
    }

    public int getThreadsAwaitingConnection() {
        return (dataSource != null && !dataSource.isClosed())
            ? dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
            : 0;
    }

    @Override
    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            LOGGER.info("Closing database connection pool...");
            dataSource.close();
            dataSource = null;
        }
    }
}
