package dev.lyy.friendlysync;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.database.repository.*;
import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.session.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class LeaseOverlapTest {
    @Test void stalledHeartbeatDoesNotStartAnotherAndFailureAllowsRetry() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        sessions.registerSession(new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 1, "Player", SessionState.ACTIVE));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(rows.renewLeases(any(), any())).thenAnswer(inv -> {
            started.countDown();
            if (!finish.await(3, TimeUnit.SECONDS)) throw new AssertionError("test timed out");
            throw new java.sql.SQLException("lock wait timeout");
        });
        var service = new PlayerOwnershipService(config, rows, history, sessions, null, id -> true);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(service::renewActiveLeases);
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                service.renewActiveLeases();
                verify(rows, times(1)).renewLeases(any(), any());
            } finally { finish.countDown(); }
            first.get(2, TimeUnit.SECONDS);
        }
        service.renewActiveLeases();
        verify(rows, times(2)).renewLeases(any(), any());
        assertThat(sessions.getLocalSession(uuid)).isPresent();
        verifyNoInteractions(history);
    }

    @Test
    void sessionReleasedByTransferDuringRenewalIsNotReportedAsFenced() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var metrics = mock(dev.lyy.friendlysync.metrics.MetricsService.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        var transferring = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 7, 3, "Player", SessionState.ACTIVE);
        var staying = new ActivePlayerSession(UUID.randomUUID(), UUID.randomUUID(), "source", 2, 1, "Other", SessionState.ACTIVE);
        sessions.registerSession(transferring);
        sessions.registerSession(staying);
        // The transfer commits and releases ownership while the batched renewal is in flight.
        when(rows.renewLeases(any(), any())).thenAnswer(inv -> {
            sessions.removeSession(transferring.toIdentity());
            transferring.forceState(SessionState.RELEASED);
            return Set.of(staying.getPlayerUuid());
        });
        var service = new PlayerOwnershipService(config, rows, history, sessions, metrics, id -> true);

        service.renewActiveLeases();

        verify(metrics, never()).recordConflict();
        verify(history).recordHeartbeats(List.of(staying.getSessionId()));
        assertThat(sessions.getLocalSession(staying.getPlayerUuid())).containsSame(staying);
    }

    @Test
    void liveSessionWhoseLeaseWasTakenIsInvalidatedAndThePlayerDisconnected() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var metrics = mock(dev.lyy.friendlysync.metrics.MetricsService.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        sessions.registerSession(new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 4, 1, "Player", SessionState.ACTIVE));
        when(rows.renewLeases(any(), any())).thenReturn(Set.of());
        when(rows.findMetadata(uuid)).thenReturn(Optional.of(
            new PlayerStateMetadata(9, 5, UUID.randomUUID(), "other", "hash")));
        var service = new PlayerOwnershipService(config, rows, history, sessions, metrics, id -> true);
        List<UUID> disconnected = new ArrayList<>();
        service.setOnSessionFenced(disconnected::add);

        service.renewActiveLeases();

        verify(metrics).recordConflict();
        assertThat(sessions.getLocalSession(uuid)).isEmpty();
        assertThat(disconnected).containsExactly(uuid);
    }

    @Test
    void unconfirmedRenewalKeepsSessionWhileSqlStillListsItAsOwner() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var metrics = mock(dev.lyy.friendlysync.metrics.MetricsService.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        var session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 4, 1, "Player", SessionState.ACTIVE);
        sessions.registerSession(session);
        when(rows.renewLeases(any(), any())).thenReturn(Set.of());
        when(rows.findMetadata(uuid)).thenReturn(Optional.of(
            new PlayerStateMetadata(1, session.getFencingToken(), session.getSessionId(), "SOURCE", "hash")));
        var service = new PlayerOwnershipService(config, rows, history, sessions, metrics, id -> true);
        List<UUID> disconnected = new ArrayList<>();
        service.setOnSessionFenced(disconnected::add);

        service.renewActiveLeases();

        assertThat(sessions.getLocalSession(uuid)).containsSame(session);
        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);
        verify(metrics, never()).recordConflict();
        assertThat(disconnected).isEmpty();
    }

    @Test
    void unverifiableOwnershipAfterFailedRenewalKeepsTheSession() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        when(config.getSessionLeaseTimeout()).thenReturn(Duration.ofSeconds(45));
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        sessions.registerSession(new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 4, 1, "Player", SessionState.ACTIVE));
        when(rows.renewLeases(any(), any())).thenReturn(Set.of());
        when(rows.findMetadata(uuid)).thenThrow(new java.sql.SQLException("connection reset"));
        var service = new PlayerOwnershipService(config, rows, history, sessions, null, id -> true);
        List<UUID> disconnected = new ArrayList<>();
        service.setOnSessionFenced(disconnected::add);

        service.renewActiveLeases();

        assertThat(sessions.getLocalSession(uuid)).isPresent();
        assertThat(disconnected).isEmpty();
    }

    @Test
    void breakExpiredLeaseWithNullOrBlankOwnerCannotReleaseAnUnverifiedSession() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();

        when(rows.forceReleaseOwnership(uuid, "OFFLINE")).thenReturn(true);

        var service = new PlayerOwnershipService(config, rows, history, sessions, null, id -> true);

        // Test with null expectedOwner
        boolean nullResult = service.breakExpiredLease(uuid, null, java.time.Instant.now());
        assertThat(nullResult).isFalse();
        verify(rows, never()).forceReleaseOwnership(uuid, "OFFLINE");
        verify(rows, never()).breakExpiredLease(any(), any(), any());

        // Test with blank expectedOwner
        boolean blankResult = service.breakExpiredLease(uuid, "   ", java.time.Instant.now());
        assertThat(blankResult).isFalse();
        verify(rows, never()).forceReleaseOwnership(uuid, "OFFLINE");
        verify(rows, never()).breakExpiredLease(any(), any(), any());
    }

    @Test
    void breakExpiredLeaseWithValidOwnerCallsRepositoryBreakExpiredLease() throws Exception {
        var config = mock(FriendlySyncConfig.class);
        var rows = mock(PlayerStateRepository.class);
        var history = mock(SessionRepository.class);
        var sessions = new PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        java.time.Instant expiry = java.time.Instant.now();

        when(rows.breakExpiredLease(uuid, "server-2", expiry)).thenReturn(true);

        var service = new PlayerOwnershipService(config, rows, history, sessions, null, id -> true);

        boolean result = service.breakExpiredLease(uuid, "server-2", expiry);
        assertThat(result).isTrue();
        verify(rows, times(1)).breakExpiredLease(uuid, "server-2", expiry);
        verify(rows, never()).forceReleaseOwnership(any(), any());
    }
}
