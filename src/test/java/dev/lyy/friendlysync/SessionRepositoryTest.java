package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import dev.lyy.friendlysync.database.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRepositoryTest {

    @Test
    void heartbeatBatchUsesSqlWithoutStatementTerminator() throws Exception {
        DatabaseManager manager = mock(DatabaseManager.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        UUID firstSession = UUID.randomUUID();
        UUID secondSession = UUID.randomUUID();

        when(manager.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(sql.capture())).thenReturn(statement);

        new SessionRepository(manager).recordHeartbeats(List.of(firstSession, secondSession));

        assertThat(sql.getValue().strip()).doesNotEndWith(";");
        verify(statement).setString(1, firstSession.toString());
        verify(statement).setString(1, secondSession.toString());
        verify(statement, times(2)).addBatch();
        verify(statement).executeBatch();
    }
}
