package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.repository.PersistedPlayerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedPlayerStateTest {

    @Test
    @DisplayName("PersistedPlayerState: Defensive copying prevents external mutation of payload")
    void testDefensiveCopy() {
        UUID uuid = UUID.randomUUID();
        byte[] original = new byte[]{1, 2, 3, 4, 5};
        Instant now = Instant.now();

        PersistedPlayerState state = new PersistedPlayerState(
            uuid, "Steve", 10L, 1L, UUID.randomUUID(), "server-1",
            "ACTIVE", now.plusSeconds(30), original, 2, "dummyHash", now, now
        );

        // Mutate original array
        original[0] = 99;
        assertThat(state.payload()[0]).isEqualTo((byte) 1);

        // Mutate array returned from accessor
        byte[] retrieved = state.payload();
        retrieved[0] = 88;
        assertThat(state.payload()[0]).isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("PersistedPlayerState: equals and hashCode use deep array equality for payload")
    void testDeepArrayEquality() {
        UUID uuid = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        PersistedPlayerState state1 = new PersistedPlayerState(
            uuid, "Steve", 10L, 1L, session, "server-1",
            "ACTIVE", now, new byte[]{1, 2, 3}, 2, "hash1", now, now
        );

        PersistedPlayerState state2 = new PersistedPlayerState(
            uuid, "Steve", 10L, 1L, session, "server-1",
            "ACTIVE", now, new byte[]{1, 2, 3}, 2, "hash1", now, now
        );

        assertThat(state1).isEqualTo(state2);
        assertThat(state1.hashCode()).isEqualTo(state2.hashCode());
    }
}
