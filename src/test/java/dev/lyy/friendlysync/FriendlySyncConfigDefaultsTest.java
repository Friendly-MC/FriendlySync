package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FriendlySyncConfigDefaultsTest {

    @Test
    @DisplayName("Shipped config.yml matches performance defaults")
    void testShippedConfigDefaults() {
        File configFile = new File("src/main/resources/config.yml");
        assertThat(configFile).exists();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        yaml.set("server.id", "test-server");
        yaml.set("database.password", "test-password");
        FriendlySyncConfig config = new FriendlySyncConfig(yaml);

        // Requirement 6: Heartbeat interval default is 30s to reduce DB traffic
        assertThat(config.getSessionHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));

        // Requirement 4: Statistics module disabled by default (mirroring MC-Data-Bridge)
        assertThat(config.isStatisticsEnabled()).isFalse();

        // Requirement 2: Continuous snapshot inserts disabled by default
        assertThat(config.isSnapshotsContinuousInserts()).isFalse();
        assertThat(config.isSnapshotsEnabled()).isTrue();
        assertThat(config.getShutdownTransferTarget()).isEmpty();
        assertThat(config.isShutdownFallbackToDisconnect()).isTrue();
    }

    @Test
    @DisplayName("Empty config falls back to safe performance defaults")
    void testEmptyConfigDefaults() {
        YamlConfiguration emptyYaml = new YamlConfiguration();
        emptyYaml.set("server.id", "test-server");
        emptyYaml.set("database.password", "test-password");
        FriendlySyncConfig config = new FriendlySyncConfig(emptyYaml);

        assertThat(config.getSessionHeartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.isStatisticsEnabled()).isFalse();
        assertThat(config.isSnapshotsContinuousInserts()).isFalse();
    }

    @Test
    void shutdownTargetUsesBackendConfigAndRejectsSelfTransfer() {
        var yaml = new YamlConfiguration();
        yaml.set("server.id", "economy-a");
        yaml.set("database.password", "test-password");
        yaml.set("shutdown.transfer-target", " spawn-a ");
        assertThat(new FriendlySyncConfig(yaml).getShutdownTransferTarget()).isEqualTo("spawn-a");
        yaml.set("shutdown.transfer-target", "ECONOMY-A");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FriendlySyncConfig(yaml))
            .isInstanceOf(dev.lyy.friendlysync.config.ConfigValidationException.class);
    }

    @Test
    void transferTimeoutMustBePositive() {
        for (String timeout : new String[]{"0s", "-1s"}) {
            var yaml = new YamlConfiguration();
            yaml.set("server.id", "economy-a");
            yaml.set("database.password", "test-password");
            yaml.set("transfer.timeout", timeout);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FriendlySyncConfig(yaml))
                .isInstanceOf(dev.lyy.friendlysync.config.ConfigValidationException.class);
        }
    }

    @Test
    void checkpointIntervalRejectsInvalidSchedulerPeriods() {
        for (String interval : new String[]{"0s", "-1s", "500ms", "25h"}) {
            var yaml = new YamlConfiguration();
            yaml.set("server.id", "test-server");
            yaml.set("database.password", "test-password");
            yaml.set("sync.checkpoint-interval", interval);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FriendlySyncConfig(yaml))
                .isInstanceOf(dev.lyy.friendlysync.config.ConfigValidationException.class);
        }
    }
}
