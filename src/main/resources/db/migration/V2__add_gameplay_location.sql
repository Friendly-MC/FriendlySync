-- FriendlySync Migration V2: Add Gameplay Location Resume Columns

ALTER TABLE friendlysync_players
    ADD COLUMN last_game_server VARCHAR(64) NULL,
    ADD COLUMN last_game_world VARCHAR(64) NULL,
    ADD COLUMN last_x DOUBLE NULL,
    ADD COLUMN last_y DOUBLE NULL,
    ADD COLUMN last_z DOUBLE NULL,
    ADD COLUMN last_yaw FLOAT NULL,
    ADD COLUMN last_pitch FLOAT NULL;

CREATE INDEX idx_fsync_players_game_server ON friendlysync_players (last_game_server);
