-- FriendlySync Migration V3: Add Migration and Player Ledgers

CREATE TABLE IF NOT EXISTS friendlysync_migrations (
    migration_id VARCHAR(64) NOT NULL,
    migration_type VARCHAR(32) NOT NULL,
    source_fingerprint VARCHAR(128) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    status VARCHAR(32) NOT NULL,
    players_discovered INT NOT NULL DEFAULT 0,
    players_imported INT NOT NULL DEFAULT 0,
    players_failed INT NOT NULL DEFAULT 0,
    players_skipped INT NOT NULL DEFAULT 0,
    critical_failures INT NOT NULL DEFAULT 0,
    plugin_version VARCHAR(32) NOT NULL,
    metadata LONGTEXT NULL,
    PRIMARY KEY (migration_id),
    INDEX idx_fsync_migrations_status (status),
    INDEX idx_fsync_migrations_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendlysync_migration_players (
    migration_id VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    username VARCHAR(16) NULL,
    source_checksum VARCHAR(64) NULL,
    target_payload_hash VARCHAR(64) NULL,
    source_updated_at BIGINT NULL,
    target_revision BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    migrated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (migration_id, player_uuid),
    INDEX idx_fsync_mig_players_uuid (player_uuid),
    INDEX idx_fsync_mig_players_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
