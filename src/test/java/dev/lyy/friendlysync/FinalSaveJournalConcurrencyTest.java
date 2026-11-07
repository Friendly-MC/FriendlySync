package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.session.FinalSaveJournal;
import dev.lyy.friendlysync.util.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinalSaveJournalConcurrencyTest {
    @TempDir Path directory;
    private FinalSaveJournal.Entry entry(UUID uuid, byte value) {
        byte[] bytes = {value};
        return new FinalSaveJournal.Entry(new SessionIdentity(uuid, UUID.randomUUID(), "source", 1), 4, 3, bytes, DigestUtils.sha256(bytes));
    }

    @Test
    void blockedSqlRecoveryDoesNotBlockAnotherPlayersJournalAndSamePlayerRemainsSerialized() throws Exception {
        var repository = mock(PlayerStateRepository.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), sameStarted = new CountDownLatch(1);
        when(repository.findMetadata(first)).thenAnswer(inv -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test recovery never released");
            return Optional.empty();
        });
        var journal = new FinalSaveJournal(directory, repository);
        var original = entry(first, (byte) 1);
        var newer = entry(first, (byte) 2);
        var other = entry(second, (byte) 3);
        journal.store(original);
        try (var worker = Executors.newFixedThreadPool(3)) {
            try {
                var recovery = worker.submit(() -> journal.recover(first));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                var otherStore = worker.submit(() -> { journal.store(other); return null; });
                otherStore.get(1, TimeUnit.SECONDS);
                assertThat(journal.read(second).payload()).containsExactly((byte) 3);
                var sameStore = worker.submit(() -> { sameStarted.countDown(); journal.store(newer); return null; });
                assertThat(sameStarted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> sameStore.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                recovery.get(1, TimeUnit.SECONDS);
                sameStore.get(1, TimeUnit.SECONDS);
                assertThat(journal.readConflict(first).hash()).isEqualTo(original.hash());
                assertThat(journal.read(first).hash()).isEqualTo(newer.hash());
                var locks = FinalSaveJournal.class.getDeclaredField("locks"); locks.setAccessible(true);
                assertThat((java.util.Map<?, ?>) locks.get(journal)).isEmpty();
            } finally { release.countDown(); }
        }
    }
}
