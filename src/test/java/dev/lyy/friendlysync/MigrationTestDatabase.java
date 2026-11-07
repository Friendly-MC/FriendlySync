package dev.lyy.friendlysync;

import dev.lyy.friendlysync.database.DatabaseManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

/** Adapts historical MySQL DDL for the in-memory migration tests. */
final class MigrationTestDatabase {
    static DatabaseManager manager(String url) throws Exception {
        DatabaseManager manager = mock(DatabaseManager.class);
        when(manager.getConnection()).thenAnswer(ignored -> {
            Connection connection = DriverManager.getConnection(url, "sa", "");
            Connection wrapped = spy(connection);
            doAnswer(call -> {
                Statement statement = connection.createStatement();
                Statement adapted = spy(statement);
                doAnswer(execution -> executeOnH2(statement, execution.getArgument(0)))
                    .when(adapted).execute(anyString());
                return adapted;
            }).when(wrapped).createStatement();
            return wrapped;
        });
        return manager;
    }

    static boolean executeOnH2(Statement statement, String sql) throws SQLException {
        // H2's MySQL mode does not support MySQL's multiple ADD COLUMN clauses.
        // Adapt only the historical V2 statement without changing its resource/checksum.
        if (sql.contains("ALTER TABLE friendlysync_players") && sql.contains("ADD COLUMN last_game_server")) {
            String columns = sql.substring(sql.indexOf("ADD COLUMN"));
            for (String column : columns.split(",")) {
                statement.execute("ALTER TABLE friendlysync_players " + column.trim());
            }
            return false;
        }
        return statement.execute(sql);
    }

}
