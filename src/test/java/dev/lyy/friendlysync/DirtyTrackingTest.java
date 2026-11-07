package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class DirtyTrackingTest {

    @Test
    @DisplayName("Dirty Tracking: Mutations increment dirty generation and committed generation updates correctly")
    void testDirtyGenerationTracking() {
        ActivePlayerSession session = new ActivePlayerSession(
            UUID.randomUUID(), UUID.randomUUID(), "server-1", 1, 0, "TestUser", SessionState.ACTIVE
        );

        assertThat(session.isDirty()).isFalse();
        assertThat(session.getDirtyGeneration()).isEqualTo(0L);

        // Player modifies inventory
        long gen1 = session.markDirty();
        assertThat(gen1).isEqualTo(1L);
        assertThat(session.isDirty()).isTrue();

        // Older commit of generation 1 begins...
        // While commit is in flight, player picks up an item (generation 2)
        long gen2 = session.markDirty();
        assertThat(gen2).isEqualTo(2L);
        assertThat(session.isDirty()).isTrue();

        // Commit for generation 1 completes and records committed generation 1
        session.recordCommittedGeneration(1L);

        // Crucial invariant: Session MUST remain dirty because generation 2 has NOT been committed!
        assertThat(session.isDirty()).isTrue();
        assertThat(session.getLastCommittedGeneration()).isEqualTo(1L);

        // Now commit of generation 2 completes
        session.recordCommittedGeneration(2L);

        // Session is now clean
        assertThat(session.isDirty()).isFalse();
    }

    @Test
    @DisplayName("Dirty Tracking: Checkpoint submits each dirty player to the bounded save pipeline")
    void testCheckpointSubmission() {
        dev.lyy.friendlysync.session.PlayerSessionService sessionService = new dev.lyy.friendlysync.session.PlayerSessionService();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        ActivePlayerSession s1 = new ActivePlayerSession(p1, UUID.randomUUID(), "server-1", 1, 1, "User1", SessionState.ACTIVE);
        ActivePlayerSession s2 = new ActivePlayerSession(p2, UUID.randomUUID(), "server-1", 1, 1, "User2", SessionState.ACTIVE);
        ActivePlayerSession s3 = new ActivePlayerSession(p3, UUID.randomUUID(), "server-1", 1, 1, "User3", SessionState.ACTIVE);

        s1.markDirty();
        s2.markDirty();
        s3.markDirty();

        sessionService.registerSession(s1);
        sessionService.registerSession(s2);
        sessionService.registerSession(s3);

        java.util.List<UUID> savedUuids = new java.util.ArrayList<>();
        dev.lyy.friendlysync.scheduler.PlatformScheduler scheduler = org.mockito.Mockito.mock(dev.lyy.friendlysync.scheduler.PlatformScheduler.class);

        dev.lyy.friendlysync.sync.DirtyTrackingService dirtyService =
            new dev.lyy.friendlysync.sync.DirtyTrackingService(sessionService, savedUuids::add, scheduler);
        dirtyService.runCheckpoint();

        // All players enter the bounded worker/save pipeline immediately. This avoids
        // delayed duplicates crossing into the next checkpoint interval.
        assertThat(savedUuids).hasSize(3);
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never())
            .runAsyncLater(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    @DisplayName("Dirty Tracking: configured coalesce delay triggers a save")
    void testCoalescedSave() {
        dev.lyy.friendlysync.session.PlayerSessionService sessionService = new dev.lyy.friendlysync.session.PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
            uuid, UUID.randomUUID(), "server-1", 1, 1, "User", SessionState.ACTIVE);
        sessionService.registerSession(session);

        java.util.List<UUID> savedUuids = new java.util.ArrayList<>();
        dev.lyy.friendlysync.scheduler.PlatformScheduler scheduler = org.mockito.Mockito.mock(dev.lyy.friendlysync.scheduler.PlatformScheduler.class);
        org.mockito.Mockito.when(scheduler.runAsyncLater(org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        });

        dev.lyy.friendlysync.sync.DirtyTrackingService dirtyService =
            new dev.lyy.friendlysync.sync.DirtyTrackingService(
                sessionService, savedUuids::add, scheduler, java.time.Duration.ZERO);
        dirtyService.markDirty(uuid);

        assertThat(savedUuids).containsExactly(uuid);
    }

    @Test
    @DisplayName("Dirty Tracking: markDirty marks session inventory dirty and handles unknown/inactive sessions")
    void testMarkDirtyMarksInventoryDirty() {
        dev.lyy.friendlysync.session.PlayerSessionService sessionService = new dev.lyy.friendlysync.session.PlayerSessionService();
        UUID uuid = UUID.randomUUID();
        ActivePlayerSession session = new ActivePlayerSession(
            uuid, UUID.randomUUID(), "server-1", 1, 1, "User", SessionState.ACTIVE);
        sessionService.registerSession(session);

        assertThat(session.isInventoryDirty()).isFalse();

        dev.lyy.friendlysync.sync.DirtyTrackingService dirtyService =
            new dev.lyy.friendlysync.sync.DirtyTrackingService(sessionService, u -> {});
        long gen = dirtyService.markDirty(uuid);

        assertThat(gen).isGreaterThan(0);
        assertThat(session.isInventoryDirty()).isTrue();
        assertThat(session.isDirty()).isTrue();

        // Edge case: unknown session
        UUID unknown = UUID.randomUUID();
        assertThat(dirtyService.markDirty(unknown)).isEqualTo(-1);

        // Edge case: inactive session
        UUID inactiveUuid = UUID.randomUUID();
        ActivePlayerSession inactiveSession = new ActivePlayerSession(
            inactiveUuid, UUID.randomUUID(), "server-1", 1, 1, "InactiveUser", SessionState.RELEASED);
        sessionService.registerSession(inactiveSession);
        assertThat(dirtyService.markDirty(inactiveUuid)).isEqualTo(-1);
        assertThat(inactiveSession.isInventoryDirty()).isFalse();
    }
}

