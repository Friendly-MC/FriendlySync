package dev.lyy.friendlysync.database.repository;

import java.util.UUID;

/** Ownership and commit identity without downloading the player payload. */
public record PlayerStateMetadata(long revision, long fencingToken, UUID activeSessionId,
                                  String ownerServer, String payloadHash) {
}
