package dev.lyy.friendlysync.database.migration;

/**
 * Representation of a versioned SQL migration script file.
 */
public record MigrationScript(
    int version,
    String description,
    String scriptName,
    String sqlContent,
    String checksum
) implements Comparable<MigrationScript> {

    @Override
    public int compareTo(MigrationScript o) {
        return Integer.compare(this.version, o.version);
    }
}
