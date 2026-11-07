-- FriendlySync Migration V4: Add Persistent Player Snapshots Table

CREATE TABLE IF NOT EXISTS friendlysync_snapshots (
    snapshot_id VARCHAR(36) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    server_id VARCHAR(64) NOT NULL,
    sync_reason VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'COMMITTED',
    payload LONGBLOB NOT NULL,
    payload_version INT NOT NULL DEFAULT 1,
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id),
    INDEX idx_fsync_snap_player (player_uuid),
    INDEX idx_fsync_snap_rev (revision),
    INDEX idx_fsync_snap_player_rev (player_uuid, revision),
    INDEX idx_fsync_snap_created (created_at),
    INDEX idx_fsync_snap_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
