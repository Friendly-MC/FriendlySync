package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.migration.DatabaseMigrator;
import dev.lyy.friendlysync.database.repository.ServerPresenceRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPresenceRepositoryTest {

    @Test
    void rejectsDuplicateLiveServerIdAndAllowsStaleTakeover() throws Exception {
        String url = "jdbc:h2:mem:server_presence_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DatabaseManager manager = MigrationTestDatabase.manager(url);
        new DatabaseMigrator(manager).migrate();

        ServerPresenceRepository repository = new ServerPresenceRepository(manager);
        UUID firstInstance = UUID.randomUUID();
        UUID secondInstance = UUID.randomUUID();

        assertThat(repository.registerServer(
            "economy", firstInstance, "1.0", "1.21.4", "Paper", Duration.ofSeconds(45)
        )).isTrue();
        assertThat(repository.registerServer(
            "economy", secondInstance, "1.0", "1.21.4", "Paper", Duration.ofSeconds(45)
        )).isFalse();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                 "UPDATE friendlysync_server_presence SET last_heartbeat = DATEADD('SECOND', -120, CURRENT_TIMESTAMP) WHERE server_id = ?")) {
            statement.setString(1, "economy");
            statement.executeUpdate();
        }

        assertThat(repository.registerServer(
            "economy", secondInstance, "1.0", "1.21.4", "Paper", Duration.ofSeconds(45)
        )).isTrue();
        assertThat(repository.getActiveServers(Duration.ofMinutes(1)))
            .singleElement()
            .extracting(info -> info.instanceId())
            .isEqualTo(secondInstance);

        assertThat(repository.unregisterServer("economy", firstInstance)).isFalse();
        assertThat(repository.unregisterServer("economy", secondInstance)).isTrue();
        assertThat(repository.registerServer(
            "economy", firstInstance, "1.0", "1.21.4", "Paper", Duration.ofSeconds(45)
        )).isTrue();
    }
}
