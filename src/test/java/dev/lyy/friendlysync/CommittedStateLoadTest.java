package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.repository.*;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.result.LoadResult;
import dev.lyy.friendlysync.scheduler.*;
import dev.lyy.friendlysync.serialization.PlayerStateSerializer;
import dev.lyy.friendlysync.session.*;
import dev.lyy.friendlysync.sync.SyncModuleManager;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommittedStateLoadTest {
    @Test void healthyPrimaryLoadsWithoutReadingHistoricalPayloadsOrRevision() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        var repository = mock(PlayerStateRepository.class);
        var history = mock(PlayerSnapshotRepository.class);
        when(repository.getSnapshotRepository()).thenReturn(history);
        when(repository.find(uuid)).thenReturn(Optional.of(row(uuid, bytes, DigestUtils.sha256(bytes))));
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.deserialize(bytes)).thenReturn(snapshot(uuid));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var result = service(repository, serializer, workers).loadState(uuid).get(2, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(LoadResult.Success.class);
            assertThat(((LoadResult.Success) result).snapshot().revision()).isEqualTo(4);
            verifyNoInteractions(history);
        }
    }

    @Test void corruptionCanRecoverOnlyExactCommittedCopyAndNeverWritesDuringLoad() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] correct = {1, 2, 3}, corrupt = {9};
        String hash = DigestUtils.sha256(correct);
        var repository = mock(PlayerStateRepository.class);
        var history = mock(PlayerSnapshotRepository.class);
        when(repository.getSnapshotRepository()).thenReturn(history);
        when(repository.find(uuid)).thenReturn(Optional.of(row(uuid, corrupt, hash)));
        when(history.findExactCommittedSnapshot(uuid, 4, hash)).thenReturn(Optional.of(
            new PlayerSnapshotRecord(UUID.randomUUID().toString(), uuid, 4, 1, UUID.randomUUID(),
                "economy-a", "FINAL_QUIT", "COMMITTED", correct, 3, hash, Instant.now())));
        var serializer = mock(PlayerStateSerializer.class);
        when(serializer.deserialize(correct)).thenReturn(snapshot(uuid));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var result = service(repository, serializer, workers).loadState(uuid).get(2, TimeUnit.SECONDS);
            assertThat(result).isInstanceOf(LoadResult.Success.class);
            assertThat(((LoadResult.Success) result).snapshot().revision()).isEqualTo(4);
            verify(history).findExactCommittedSnapshot(uuid, 4, hash);
            verify(history, never()).findLatestValidSnapshot(any());
            verify(serializer, never()).serialize(any());
        }
    }

    private PlayerStateService service(PlayerStateRepository repository, PlayerStateSerializer serializer, ExecutorService workers) {
        return new PlayerStateService(repository, serializer, new PlayerOperationQueue(workers),
            mock(SyncModuleManager.class), new PlayerSessionService(), mock(PlatformScheduler.class), workers, null);
    }

    private PersistedPlayerState row(UUID uuid, byte[] payload, String hash) {
        return new PersistedPlayerState(uuid, "Player", 4, 2, UUID.randomUUID(), "spawn-a", "LOADING",
            Instant.now().plusSeconds(45), payload, 3, hash, Instant.now(), Instant.now());
    }

    private PlayerStateSnapshot snapshot(UUID uuid) {
        return new PlayerStateSnapshot(uuid, 3, 1, null, null, null, null, null,
            List.of(), null, null, null, null, null, Map.of(), Instant.now(), 3);
    }
}
