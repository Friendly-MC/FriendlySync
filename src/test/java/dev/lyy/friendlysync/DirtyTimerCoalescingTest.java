package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.*;
import dev.lyy.friendlysync.session.PlayerSessionService;
import dev.lyy.friendlysync.scheduler.*;
import dev.lyy.friendlysync.sync.DirtyTrackingService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirtyTimerCoalescingTest {
    final UUID uuid = UUID.randomUUID();
    final PlayerSessionService sessions = new PlayerSessionService();
    final ActivePlayerSession session = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 1, 0, "Player", SessionState.ACTIVE);
    final PlatformScheduler scheduler = mock(PlatformScheduler.class);
    final AtomicLong clock = new AtomicLong();
    final List<Runnable> callbacks = new ArrayList<>();
    final List<Duration> delays = new ArrayList<>();
    final List<ScheduledTaskHandle> handles = new ArrayList<>();
    final List<UUID> saves = new ArrayList<>();
    final DirtyTrackingService service;

    DirtyTimerCoalescingTest() {
        sessions.registerSession(session);
        when(scheduler.runAsyncLater(any(), any())).thenAnswer(inv -> {
            delays.add(inv.getArgument(0));
            callbacks.add(inv.getArgument(1));
            var handle = mock(ScheduledTaskHandle.class);
            handles.add(handle);
            return handle;
        });
        service = new DirtyTrackingService(sessions, saves::add, scheduler, Duration.ofSeconds(2), clock::get);
    }

    @Test void thousandMutationsUseOneTimerAndRetainEveryGeneration() {
        for (int i = 0; i < 1000; i++) service.markDirty(uuid);
        assertThat(callbacks).hasSize(1);
        assertThat(session.getDirtyGeneration()).isEqualTo(1000);
        clock.set(Duration.ofSeconds(2).toNanos());
        callbacks.getFirst().run();
        assertThat(saves).containsExactly(uuid);
        assertThat(session.isDirty()).isTrue(); // Only a confirmed save may clear it.
    }

    @Test void mutationExtendsQuietPeriodWithoutCreatingAnotherTimer() {
        service.markDirty(uuid);
        clock.set(Duration.ofMillis(1500).toNanos());
        service.markDirty(uuid);
        assertThat(callbacks).hasSize(1);
        clock.set(Duration.ofSeconds(2).toNanos());
        callbacks.get(0).run();
        assertThat(saves).isEmpty();
        assertThat(delays).containsExactly(Duration.ofSeconds(2), Duration.ofMillis(1500));
        clock.set(Duration.ofMillis(3500).toNanos());
        callbacks.get(1).run();
        assertThat(saves).containsExactly(uuid);
    }

    @Test void cancelledOldCallbackCannotConsumeNewSessionsTimer() {
        service.markDirty(uuid);
        service.clear(uuid);
        verify(handles.getFirst()).cancel();
        sessions.removeSession(session.toIdentity());
        var next = new ActivePlayerSession(uuid, UUID.randomUUID(), "source", 2, 1, "Player", SessionState.ACTIVE);
        sessions.registerSession(next);
        service.markDirty(uuid);
        clock.set(Duration.ofSeconds(2).toNanos());
        callbacks.get(0).run();
        assertThat(saves).isEmpty();
        callbacks.get(1).run();
        assertThat(saves).containsExactly(uuid);
    }

    @Test void schedulingFailureLeavesDirtyStateForCheckpointAndAllowsRetry() {
        when(scheduler.runAsyncLater(any(), any())).thenThrow(new java.util.concurrent.RejectedExecutionException());
        assertThatThrownBy(() -> service.markDirty(uuid)).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        service.runCheckpoint();
        assertThat(saves).containsExactly(uuid);
        assertThat(session.isDirty()).isTrue();
        doReturn(mock(ScheduledTaskHandle.class)).when(scheduler).runAsyncLater(any(), any());
        service.markDirty(uuid);
        verify(scheduler, times(2)).runAsyncLater(any(), any());
    }
}
