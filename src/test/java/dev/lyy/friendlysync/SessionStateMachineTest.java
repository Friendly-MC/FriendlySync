package dev.lyy.friendlysync;

import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class SessionStateMachineTest {

    @Test
    @DisplayName("State Machine: Valid lifecycle transitions succeed")
    void testValidTransitions() {
        ActivePlayerSession session = new ActivePlayerSession(
            UUID.randomUUID(), UUID.randomUUID(), "server-1", 1, 0, "TestUser", SessionState.OFFLINE
        );

        assertThat(session.getState()).isEqualTo(SessionState.OFFLINE);

        // OFFLINE -> ACQUIRING
        assertThat(session.transitionTo(SessionState.ACQUIRING)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.ACQUIRING);

        // ACQUIRING -> LOADING
        assertThat(session.transitionTo(SessionState.LOADING)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.LOADING);

        // LOADING -> ACTIVE
        assertThat(session.transitionTo(SessionState.ACTIVE)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);

        // ACTIVE -> FREEZING
        assertThat(session.transitionTo(SessionState.FREEZING)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.FREEZING);

        // FREEZING -> SAVING
        assertThat(session.transitionTo(SessionState.SAVING)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.SAVING);

        // SAVING -> TRANSFER_PENDING
        assertThat(session.transitionTo(SessionState.TRANSFER_PENDING)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.TRANSFER_PENDING);

        // TRANSFER_PENDING -> RELEASED
        assertThat(session.transitionTo(SessionState.RELEASED)).isTrue();
        assertThat(session.getState()).isEqualTo(SessionState.RELEASED);
    }

    @Test
    @DisplayName("State Machine: Illegal state transitions are rejected without modifying state")
    void testIllegalTransitionsRejected() {
        ActivePlayerSession session = new ActivePlayerSession(
            UUID.randomUUID(), UUID.randomUUID(), "server-1", 1, 0, "TestUser", SessionState.OFFLINE
        );

        // OFFLINE cannot jump directly to ACTIVE or FREEZING
        assertThat(session.transitionTo(SessionState.ACTIVE)).isFalse();
        assertThat(session.getState()).isEqualTo(SessionState.OFFLINE);

        assertThat(session.transitionTo(SessionState.FREEZING)).isFalse();
        assertThat(session.getState()).isEqualTo(SessionState.OFFLINE);

        session.forceState(SessionState.ACTIVE);

        // ACTIVE cannot jump backward to LOADING or ACQUIRING
        assertThat(session.transitionTo(SessionState.LOADING)).isFalse();
        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);

        assertThat(session.transitionTo(SessionState.ACQUIRING)).isFalse();
        assertThat(session.getState()).isEqualTo(SessionState.ACTIVE);
    }
}
