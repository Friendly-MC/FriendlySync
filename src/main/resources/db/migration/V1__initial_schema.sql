-- FriendlySync Initial Database Schema V1

CREATE TABLE IF NOT EXISTS friendlysync_players (
    uuid VARCHAR(36) NOT NULL,
    username VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    active_session_id VARCHAR(36) NULL,
    owner_server VARCHAR(64) NULL,
    session_state VARCHAR(32) NOT NULL DEFAULT 'OFFLINE',
    lease_expires_at TIMESTAMP NULL,
    payload LONGBLOB NULL,
    payload_version INT NOT NULL DEFAULT 1,
    payload_hash VARCHAR(64) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid),
    INDEX idx_fsync_players_owner (owner_server),
    INDEX idx_fsync_players_lease (lease_expires_at),
    INDEX idx_fsync_players_session (active_session_id),
    INDEX idx_fsync_players_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendlysync_sessions (
    session_id VARCHAR(36) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    PRIMARY KEY (session_id),
    INDEX idx_fsync_sessions_player (player_uuid),
    INDEX idx_fsync_sessions_server (server_id),
    INDEX idx_fsync_sessions_heartbeat (heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendlysync_transfer_log (
    transfer_id VARCHAR(36) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    source_server VARCHAR(64) NOT NULL,
    target_server VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    target_revision BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    PRIMARY KEY (transfer_id),
    INDEX idx_fsync_transfer_player (player_uuid),
    INDEX idx_fsync_transfer_created (created_at),
    INDEX idx_fsync_transfer_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS friendlysync_server_presence (
    server_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    plugin_version VARCHAR(32) NOT NULL,
    minecraft_version VARCHAR(32) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    last_heartbeat TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
