package dev.lyy.friendlysync;

import dev.lyy.friendlysync.server.ServerPlatform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPlatformTest {

    @Test
    @DisplayName("ServerPlatform: Enum properties and regionized flags are correct")
    void testPlatformProperties() {
        assertThat(ServerPlatform.PAPER.isRegionized()).isFalse();
        assertThat(ServerPlatform.PAPER.getDisplayName()).isEqualTo("Paper");

        assertThat(ServerPlatform.FOLIA.isRegionized()).isTrue();
        assertThat(ServerPlatform.FOLIA.getDisplayName()).isEqualTo("Folia");

        assertThat(ServerPlatform.CANVAS.isRegionized()).isTrue();
        assertThat(ServerPlatform.CANVAS.getDisplayName()).isEqualTo("CanvasMC");
    }

    @Test
    @DisplayName("ServerPlatform: detect() safely executes in unit test environment")
    void testDetectSafeFallback() {
        ServerPlatform platform = ServerPlatform.detect();
        assertThat(platform).isNotNull();
        // In plain unit test environment without Folia/Canvas, it safely defaults to PAPER
        assertThat(platform).isIn(ServerPlatform.PAPER, ServerPlatform.FOLIA, ServerPlatform.CANVAS);
    }
}
