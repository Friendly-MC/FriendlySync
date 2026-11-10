package dev.lyy.friendlysync.database.migration;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.util.DigestUtils;
import dev.lyy.friendlysync.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, robust, transactional database migration engine.
 * Tracks schema versions in `friendlysync_schema_history` and applies versioned scripts sequentially.
 */
public final class DatabaseMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseMigrator.class);
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("^V(\\d+)__(.+)\\.sql$");
    private static final Pattern CREATE_TABLE_PATTERN =
        Pattern.compile("^(?:--[^\\n]*\\n\\s*)*CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    private final DatabaseManager databaseManager;

    public DatabaseMigrator(DatabaseManager databaseManager) {
        this.databaseManager = Preconditions.checkNotNull(databaseManager, "databaseManager cannot be null");
    }

    public void migrate() throws SQLException {
        LOGGER.info("Checking database schema status...");

        ensureHistoryTableExists();

        try (Connection lockConn = databaseManager.getConnection()) {
            boolean hasLock = acquireMigrationLock(lockConn);
            try {
                List<MigrationScript> availableScripts = discoverScripts();
                if (availableScripts.isEmpty()) {
                    LOGGER.info("No migration scripts found to apply.");
                    return;
                }

                Collections.sort(availableScripts);

                for (MigrationScript script : availableScripts) {
                    if (isApplied(script.version())) {
                        validateChecksum(script);
                    } else {
                        applyMigration(script);
                    }
                }

                repairMissingTables(availableScripts);

                LOGGER.info("Database schema is fully up to date.");
            } finally {
                if (hasLock) {
                    releaseMigrationLock(lockConn);
                }
            }
        }
    }

    /**
     * A database copied from another server can carry schema history rows while some
     * tables were never imported (for example a dump that stopped part way). History
     * then claims the version is applied and the table is never created. Recreate any
     * missing table from its idempotent CREATE TABLE IF NOT EXISTS statement. Existing
     * tables and rows are never touched.
     */
    private void repairMissingTables(List<MigrationScript> scripts) throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            for (MigrationScript script : scripts) {
                for (String sql : statements(script)) {
                    Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);
                    if (!matcher.find() || tableExists(connection, matcher.group(1))) {
                        continue;
                    }
                    String table = matcher.group(1);
                    LOGGER.warn("Table {} from migration V{} is missing although schema history marks it applied "
                        + "(incomplete database copy?). Recreating it empty; existing tables are untouched.",
                        table, script.version());
                    try (Statement stmt = connection.createStatement()) {
                        stmt.setQueryTimeout(60);
                        stmt.execute(sql);
                    }
                    if (!tableExists(connection, table)) {
                        throw new SQLException("Failed to recreate missing table " + table);
                    }
                }
            }
        }
    }

    private static List<String> statements(MigrationScript script) {
        List<String> statements = new ArrayList<>();
        String stripped = script.sqlContent()
            .replaceAll("(?m)^\\s*--.*$", "")
            .replaceAll("/\\*.*?\\*/", "");
        for (String rawStmt : stripped.split(";")) {
            String clean = rawStmt.trim();
            if (!clean.isEmpty()) {
                statements.add(clean);
            }
        }
        return statements;
    }

    /** Looks only in the connected database; a shared host may hold same-named tables elsewhere. */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        String catalog = connection.getCatalog();
        for (String candidate : new String[] { table, table.toUpperCase(java.util.Locale.ROOT) }) {
            try (ResultSet rs = connection.getMetaData().getTables(catalog, null, candidate, new String[] { "TABLE" })) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void ensureHistoryTableExists() throws SQLException {
        try (Connection connection = databaseManager.getConnection()) {
            if (tableExists(connection, "friendlysync_schema_history")) {
                return;
            }

            String sql = """
                CREATE TABLE IF NOT EXISTS friendlysync_schema_history (
                    installed_rank INT NOT NULL AUTO_INCREMENT,
                    version INT NOT NULL,
                    description VARCHAR(200) NOT NULL,
                    script VARCHAR(100) NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    installed_by VARCHAR(100) NOT NULL,
                    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    execution_time_ms INT NOT NULL,
                    success BOOLEAN NOT NULL,
                    PRIMARY KEY (installed_rank),
                    UNIQUE INDEX idx_fsync_schema_version (version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """;
            try (Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(30);
                stmt.execute(sql);
            }
        }
    }

    private boolean isApplied(int version) throws SQLException {
        String sql = "SELECT success FROM friendlysync_schema_history WHERE version = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("success");
                }
            }
        }
        return false;
    }

    private void validateChecksum(MigrationScript script) throws SQLException {
        String sql = "SELECT checksum FROM friendlysync_schema_history WHERE version = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.setInt(1, script.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String appliedChecksum = rs.getString("checksum");
                    if (!appliedChecksum.equalsIgnoreCase(script.checksum())) {
                        throw new IllegalStateException(String.format(
                            "Migration checksum mismatch for version %d (%s). Database: %s, Current: %s. Do not alter applied migration scripts!",
                            script.version(), script.scriptName(), appliedChecksum, script.checksum()
                        ));
                    }
                }
            }
        }
    }

    private void applyMigration(MigrationScript script) throws SQLException {
        if (isApplied(script.version())) {
            validateChecksum(script);
            return;
        }

        LOGGER.info("Applying migration V{}__{}...", script.version(), script.description());
        long start = System.currentTimeMillis();

        try (Connection connection = databaseManager.getConnection()) {
            boolean initialAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                try (Statement stmt = connection.createStatement()) {
                    stmt.setQueryTimeout(60);
                    for (String sql : statements(script)) {
                        try {
                            stmt.execute(sql);
                        } catch (SQLException e) {
                            if (isIgnorableIndexError(sql, e)) {
                                LOGGER.warn("Index in migration V{} already exists in database; skipping creation: {}",
                                    script.version(), e.getMessage());
                            } else {
                                throw e;
                            }
                        }
                    }
                }

                long duration = System.currentTimeMillis() - start;

                String recordSql = """
                    INSERT INTO friendlysync_schema_history (version, description, script, checksum, installed_by, execution_time_ms, success)
                    VALUES (?, ?, ?, ?, ?, ?, ?);
                    """;
                try (PreparedStatement ps = connection.prepareStatement(recordSql)) {
                    ps.setQueryTimeout(30);
                    ps.setInt(1, script.version());
                    ps.setString(2, script.description());
                    ps.setString(3, script.scriptName());
                    ps.setString(4, script.checksum());
                    ps.setString(5, "FriendlySync");
                    ps.setInt(6, (int) duration);
                    ps.setBoolean(7, true);
                    ps.executeUpdate();
                }

                connection.commit();
                LOGGER.info("Migration V{}__{} applied successfully in {}ms.", script.version(), script.description(), duration);
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    LOGGER.warn("Failed to rollback failed migration transaction: {}", rollbackEx.getMessage());
                }
                LOGGER.error("Migration V{}__{} failed: {}", script.version(), script.description(), e.getMessage(), e);
                throw new SQLException("Failed to apply migration V" + script.version() + ": " + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(initialAutoCommit);
            }
        }
    }

    private static boolean isIgnorableIndexError(String sql, SQLException e) {
        int code = e.getErrorCode();
        String message = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
        // 1061: MySQL/MariaDB ER_DUP_KEYNAME ("Duplicate key name '...'")
        // 42111: H2 INDEX_ALREADY_EXISTS_1
        boolean isDuplicateKey = (code == 1061
            || code == 42111
            || message.contains("duplicate key name")
            || message.contains("already exists")
            || message.contains("duplicate index"));

        if (!isDuplicateKey) {
            return false;
        }

        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("INDEX");
    }

    private boolean acquireMigrationLock(Connection connection) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK('friendlysync_migration_lock', 60)")) {
            ps.setQueryTimeout(65);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void releaseMigrationLock(Connection connection) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK('friendlysync_migration_lock')")) {
            ps.setQueryTimeout(10);
            ps.execute();
        } catch (Throwable ignored) {
        }
    }

    private String versionTwoScript() throws SQLException {
        // A snapshot accidentally reused V2 for the ledger migration. Preserve that
        // history and validate against its exact resource; never rewrite checksums.
        String renumberedScript = "V2__add_migration_and_player_ledgers.sql";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                "SELECT script FROM friendlysync_schema_history WHERE version = 2 AND success = TRUE")) {
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && renumberedScript.equals(rs.getString("script"))) {
                    return renumberedScript;
                }
            }
        }
        return "V2__add_gameplay_location.sql";
    }

    private List<MigrationScript> discoverScripts() throws SQLException {
        List<MigrationScript> scripts = new ArrayList<>();
        String[] scriptNames = {
            "V1__initial_schema.sql", versionTwoScript(), "V3__add_migration_and_player_ledgers.sql",
            "V4__add_player_snapshots_table.sql", "V5__performance_indexes.sql"
        };

        for (String name : scriptNames) {
            String path = "db/migration/" + name;
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    LOGGER.warn("Migration resource not found on classpath: {}", path);
                    continue;
                }
                String content;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    content = sb.toString();
                }

                Matcher matcher = SCRIPT_PATTERN.matcher(name);
                if (matcher.matches()) {
                    int version = Integer.parseInt(matcher.group(1));
                    String description = matcher.group(2).replace('_', ' ');
                    String checksum = DigestUtils.sha256(content.getBytes(StandardCharsets.UTF_8));
                    scripts.add(new MigrationScript(version, description, name, content, checksum));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load migration script {}: {}", path, e.getMessage(), e);
            }
        }
        return scripts;
    }
}
