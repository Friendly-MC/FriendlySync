package dev.lyy.friendlysync.database;

/**
 * Supported SQL database engines.
 */
public enum DatabaseType {
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://"),
    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://");

    private final String driverClassName;
    private final String jdbcPrefix;

    DatabaseType(String driverClassName, String jdbcPrefix) {
        this.driverClassName = driverClassName;
        this.jdbcPrefix = jdbcPrefix;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public String getJdbcPrefix() {
        return jdbcPrefix;
    }
}
