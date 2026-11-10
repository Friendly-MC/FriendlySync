-- FriendlySync Migration V5: Add Performance Composite Indexes for Fast Lookups and Pruning

CREATE INDEX idx_fsync_players_owner_lease ON friendlysync_players (owner_server, lease_expires_at);
CREATE INDEX idx_fsync_players_username ON friendlysync_players (username);
CREATE INDEX idx_fsync_snap_lookup ON friendlysync_snapshots (player_uuid, status, revision, created_at);
CREATE INDEX idx_fsync_sessions_started ON friendlysync_sessions (started_at);
CREATE INDEX idx_fsync_sessions_ended ON friendlysync_sessions (ended_at);

