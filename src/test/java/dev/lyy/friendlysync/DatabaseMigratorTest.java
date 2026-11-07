package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.migration.DatabaseMigrator;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import dev.lyy.friendlysync.util.DigestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static dev.lyy.friendlysync.MigrationTestDatabase.manager;
import static dev.lyy.friendlysync.MigrationTestDatabase.executeOnH2;

class DatabaseMigratorTest {

    @Test
    void installsEveryBundledMigrationIncludingPlayerLedgers() throws Exception {
        String url = "jdbc:h2:mem:migrator_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DatabaseManager manager = manager(url);

        new DatabaseMigrator(manager).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             ResultSet tables = connection.getMetaData().getTables(null, null, "friendlysync_migration_players", null)) {
            assertThat(tables.next()).isTrue();
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             ResultSet tables = connection.getMetaData().getTables(null, null, "friendlysync_snapshots", null)) {
            assertThat(tables.next()).isTrue();
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM friendlysync_schema_history WHERE success = TRUE");
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(4);
        }
    }

    @Test
    void upgradesOriginalVersionTwoWithoutChangingHistoryOrPlayerData() throws Exception {
        assertThat(DigestUtils.sha256(resource("V2__add_gameplay_location.sql")))
            .isEqualTo("e0f4a1d3e7be8728ee34ae9dbee801462781c87b4e8d7041bea859a4b68cf392");
        String url = seedVersionTwo("V2__add_gameplay_location.sql");
        migrateTwiceAndVerify(url, "V2__add_gameplay_location.sql");
    }

    @Test
    void upgradesRenumberedLedgerSnapshotWithoutRewritingItsHistory() throws Exception {
        assertThat(DigestUtils.sha256(resource("V2__add_migration_and_player_ledgers.sql")))
            .isEqualTo("db9660d4a860eb0d02c3f5913db4f8fd324900d2aaa4452024c69af43e2b87e6");
        String url = seedVersionTwo("V2__add_migration_and_player_ledgers.sql");
        migrateTwiceAndVerify(url, "V2__add_migration_and_player_ledgers.sql");
    }

    @Test
    void recreatesTablesMissingFromAnIncompleteDatabaseCopyWithoutTouchingPlayerData() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        new DatabaseMigrator(manager(url)).migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO friendlysync_players (uuid, username, revision, payload) "
                + "VALUES ('00000000-0000-0000-0000-000000000002', 'CopiedPlayer', 17, X'CAFE')");
            // A dump that stopped part way: history and players arrived, the last tables did not.
            statement.execute("DROP TABLE friendlysync_transfer_log");
            statement.execute("DROP TABLE friendlysync_snapshots");
        }

        new DatabaseMigrator(manager(url)).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO friendlysync_transfer_log (transfer_id, player_uuid, session_id, "
                + "source_server, target_server, source_revision, target_revision, status) "
                + "VALUES ('t', 'p', 's', 'a', 'b', 1, 1, 'PENDING')");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM friendlysync_snapshots")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT username, revision, payload FROM friendlysync_players")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("username")).isEqualTo("CopiedPlayer");
                assertThat(rows.getLong("revision")).isEqualTo(17);
                assertThat(rows.getBytes("payload")).containsExactly((byte) 0xCA, (byte) 0xFE);
                assertThat(rows.next()).isFalse();
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM friendlysync_schema_history")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
        }
    }

    @Test
    void rejectsUnknownChecksumsInBothVersionTwoHistories() throws Exception {
        for (String name : new String[] { "V2__add_gameplay_location.sql",
                                         "V2__add_migration_and_player_ledgers.sql" }) {
            String url = seedVersionTwo(name);
            try (Connection connection = DriverManager.getConnection(url, "sa", "");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE friendlysync_schema_history SET checksum = 'unexpected' WHERE version = 2");
            }
            assertThatThrownBy(() -> new DatabaseMigrator(manager(url)).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Migration checksum mismatch for version 2");
        }
    }

    private void migrateTwiceAndVerify(String url, String versionTwo) throws Exception {
        DatabaseMigrator migrator = new DatabaseMigrator(manager(url));
        migrator.migrate();
        migrator.migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT script, checksum FROM friendlysync_schema_history WHERE version = 2")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("script")).isEqualTo(versionTwo);
                assertThat(rows.getString("checksum")).isEqualTo(DigestUtils.sha256(resource(versionTwo)));
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM friendlysync_schema_history")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (ResultSet rows = statement.executeQuery("SELECT username, revision FROM friendlysync_players")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("username")).isEqualTo("ExistingPlayer");
                assertThat(rows.getLong("revision")).isEqualTo(42);
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    null, null, "friendlysync_migration_players", null)) {
                assertThat(tables.next()).isTrue();
            }
            try (ResultSet tables = connection.getMetaData().getTables(
                    null, null, "friendlysync_snapshots", null)) {
                assertThat(tables.next()).isTrue();
            }
        }
    }

    private String seedVersionTwo(String versionTwo) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE friendlysync_schema_history (
                    installed_rank INT AUTO_INCREMENT PRIMARY KEY,
                    version INT NOT NULL UNIQUE, description VARCHAR(200) NOT NULL,
                    script VARCHAR(100) NOT NULL, checksum VARCHAR(64) NOT NULL,
                    installed_by VARCHAR(100) NOT NULL,
                    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    execution_time_ms INT NOT NULL, success BOOLEAN NOT NULL
                )
                """);
            int version = 0;
            for (String name : new String[] { "V1__initial_schema.sql", versionTwo }) {
                String content = resource(name);
                for (String sql : content.split(";")) {
                    if (!sql.isBlank()) {
                        executeOnH2(statement, sql);
                    }
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO friendlysync_schema_history
                        (version, description, script, checksum, installed_by, execution_time_ms, success)
                        VALUES (?, ?, ?, ?, 'FriendlySync', 0, TRUE)
                        """)) {
                    insert.setInt(1, ++version);
                    insert.setString(2, name);
                    insert.setString(3, name);
                    insert.setString(4, DigestUtils.sha256(content));
                    insert.executeUpdate();
                }
            }
            statement.executeUpdate("INSERT INTO friendlysync_players (uuid, username, revision) "
                + "VALUES ('00000000-0000-0000-0000-000000000001', 'ExistingPlayer', 42)");
        }
        return url;
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .lines().collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        }
    }
}
