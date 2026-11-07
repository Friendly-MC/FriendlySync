package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import dev.lyy.friendlysync.model.stats.StatisticKey;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.sync.stats.StatisticsCapturePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsCapturePolicyTest {

    @Test
    void legacyApplyPreservesStatisticsAndCheckpointResolvesTheExistingSessionCache() {
        var config = org.mockito.Mockito.mock(dev.lyy.friendlysync.config.FriendlySyncConfig.class);
        org.mockito.Mockito.when(config.getPdcPolicy()).thenReturn(new dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy(
            false, "ALL_SAFE_KEYS", java.util.List.of(), java.util.List.of(), 65536, 16384, 16));
        org.mockito.Mockito.when(config.isStatisticsEnabled()).thenReturn(true);
        var modules = new dev.lyy.friendlysync.sync.SyncModuleManager(config);
        var sessions = new dev.lyy.friendlysync.session.PlayerSessionService();
        modules.setSessionService(sessions);
        UUID uuid = UUID.randomUUID();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 4, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        var statistics = new StatisticsSnapshot(Map.of(StatisticKey.untyped("minecraft:deaths"), 12L));
        session.cacheStatistics(statistics, Instant.now());
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(uuid);
        var legacy = new dev.lyy.friendlysync.model.PlayerStateSnapshot(uuid, 4, 1, null, null,
            null, null, null, java.util.List.of(), null, null, null, Map.of(), Instant.now(), 2);

        modules.applySnapshot(player, legacy, session, null, null);
        org.mockito.Mockito.verifyNoInteractions(player);
        assertThat(session.getCachedStatistics().orElseThrow().snapshot()).isSameAs(statistics);

        var captured = modules.captureSnapshot(player, 4, 1, dev.lyy.friendlysync.api.SyncReason.CHECKPOINT);
        assertThat(captured.statistics()).isSameAs(statistics);
        org.mockito.Mockito.verify(player, org.mockito.Mockito.times(2)).getUniqueId();
        org.mockito.Mockito.verifyNoMoreInteractions(player);
    }

    @Test
    void missingCacheAlwaysRequiresCheckpointRefresh() {
        assertThat(StatisticsCapturePolicy.checkpointRefreshDue(
            null, Duration.ofSeconds(30), Instant.parse("2026-01-01T00:00:00Z")
        )).isTrue();
    }

    @Test
    void checkpointRefreshIsRateLimitedUntilIntervalExpires() {
        Instant capturedAt = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(StatisticsCapturePolicy.checkpointRefreshDue(
            capturedAt, Duration.ofSeconds(30), capturedAt.plusSeconds(29)
        )).isFalse();
        assertThat(StatisticsCapturePolicy.checkpointRefreshDue(
            capturedAt, Duration.ofSeconds(30), capturedAt.plusSeconds(30)
        )).isTrue();
    }

    @Test
    void sessionCacheKeepsSnapshotAndTimestampTogether() {
        ActivePlayerSession session = new ActivePlayerSession(
            UUID.randomUUID(), UUID.randomUUID(), "server-a", 1L, 1L,
            "Player", SessionState.ACTIVE
        );
        StatisticsSnapshot snapshot = new StatisticsSnapshot(Map.of(
            StatisticKey.untyped("minecraft:deaths"), 3L
        ));
        Instant capturedAt = Instant.parse("2026-01-01T00:00:00Z");

        session.cacheStatistics(snapshot, capturedAt);

        assertThat(session.getCachedStatistics()).contains(
            new ActivePlayerSession.CachedStatistics(snapshot, capturedAt)
        );
    }
}
