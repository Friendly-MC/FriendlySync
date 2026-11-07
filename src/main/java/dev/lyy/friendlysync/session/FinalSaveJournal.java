package dev.lyy.friendlysync.session;

import dev.lyy.friendlysync.database.repository.PlayerStateRepository;
import dev.lyy.friendlysync.model.SessionIdentity;
import dev.lyy.friendlysync.model.result.CommitResult;
import dev.lyy.friendlysync.util.DigestUtils;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Durable final snapshots. Replays retain the original revision and fencing identity. */
public final class FinalSaveJournal {
    private static final int MAGIC = 0x46534A31;
    private static final int MAX_PAYLOAD = 64 * 1024 * 1024;
    private final java.util.concurrent.ConcurrentHashMap<UUID, PlayerLock> locks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path directory;
    private final PlayerStateRepository repository;
    private final String localServerId;

    public FinalSaveJournal(Path directory, PlayerStateRepository repository) throws IOException {
        this(directory, repository, null);
    }

    public FinalSaveJournal(Path directory, PlayerStateRepository repository, String localServerId) throws IOException {
        this.directory = directory;
        this.repository = repository;
        this.localServerId = localServerId;
        Files.createDirectories(directory);
    }

    public record Entry(SessionIdentity identity, long revision, int version, byte[] payload, String hash) {}

    public void store(Entry entry) throws IOException {
        try (PlayerLock ignored = lock(entry.identity().playerUuid())) {
            if (entry.payload().length > MAX_PAYLOAD) throw new IOException("Final snapshot exceeds journal limit");
            Path target = path(entry.identity().playerUuid());
            Path temporary = Files.createTempFile(directory, "final-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary, WRITE);
                     DataOutputStream out = new DataOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                    out.writeInt(MAGIC);
                    out.writeUTF(entry.identity().playerUuid().toString());
                    out.writeUTF(entry.identity().sessionId().toString());
                    out.writeUTF(entry.identity().serverId());
                    out.writeLong(entry.identity().fencingToken());
                    out.writeLong(entry.revision());
                    out.writeInt(entry.version());
                    out.writeUTF(entry.hash());
                    out.writeInt(entry.payload().length);
                    out.write(entry.payload());
                    out.flush();
                    channel.force(true);
                }
                // Do not fall back to a non-atomic replacement of an earlier recovery record.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public Entry read(UUID uuid) throws IOException {
        return readFile(path(uuid), uuid).orElseThrow(() -> new NoSuchFileException("No journal entry for " + uuid));
    }

    public Optional<Entry> readOptional(UUID uuid) throws IOException {
        return readFile(path(uuid), uuid);
    }

    private Optional<Entry> readFile(Path file, UUID uuid) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported journal format");
            SessionIdentity identity = new SessionIdentity(UUID.fromString(in.readUTF()),
                UUID.fromString(in.readUTF()), in.readUTF(), in.readLong());
            long revision = in.readLong();
            int version = in.readInt();
            String hash = in.readUTF();
            int size = in.readInt();
            if (size < 0 || size > MAX_PAYLOAD) throw new IOException("Invalid journal payload size");
            byte[] bytes = in.readNBytes(size);
            if (!identity.playerUuid().equals(uuid) || bytes.length != size || in.read() != -1
                || !DigestUtils.sha256(bytes).equals(hash)) throw new IOException("Corrupt final-save journal");
            return Optional.of(new Entry(identity, revision, version, bytes, hash));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid journal identity", e);
        }
    }

    public List<UUID> pendingPlayers() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".pending"))
                .map(p -> UUID.fromString(p.getFileName().toString().replace(".pending", ""))).toList();
        }
    }

    public List<UUID> conflictPlayers() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".conflict"))
                .map(p -> UUID.fromString(p.getFileName().toString().replace(".conflict", ""))).toList();
        }
    }

    /** Explicit selection may fence an expired local session matching the conflict identity. */
    public boolean resolveConflict(UUID uuid, boolean acceptCurrent) throws Exception {
        return resolveConflict(uuid, acceptCurrent, null);
    }

    public boolean resolveConflict(UUID uuid, boolean acceptCurrent, String validatedHash) throws Exception {
        try (PlayerLock ignored = lock(uuid)) {
            if (contains(uuid)) throw new IOException("A final save is still pending; retry recovery first");
            Path conflict = directory.resolve(uuid + ".conflict");
            if (!Files.exists(conflict)) return true;
            Entry entry = readFile(conflict, uuid).orElse(null);
            if (entry == null) return true;
            var row = repository.find(uuid).orElse(null);
            if (row == null || row.payload() == null || row.payloadHash() == null
                || !DigestUtils.sha256(row.payload()).equals(row.payloadHash())
                || row.revision() < entry.revision()) return false;
            if (validatedHash != null && !validatedHash.equals(row.payloadHash()))
                throw new IOException("Database payload changed during review; run the recovery command again");
            if (!acceptCurrent && !entry.hash().equals(row.payloadHash())) return false;
            boolean releaseOwner = acceptCurrent && row.ownerServer() != null;
            if (releaseOwner && (localServerId == null || !localServerId.equals(row.ownerServer())
                || !localServerId.equals(entry.identity().serverId())
                || !entry.identity().sessionId().equals(row.activeSessionId())
                || entry.identity().fencingToken() != row.fencingToken()
                || !"ACTIVE".equals(row.sessionState()) || row.leaseExpiresAt() == null
                || !row.leaseExpiresAt().isBefore(java.time.Instant.now()))) {
                throw new IOException("Ownership is not an expired local session matching this conflict; recovery remains blocked");
            }
            Path archive = directory.resolve("resolved").resolve(uuid + "-" + UUID.randomUUID());
            Files.createDirectories(archive);
            Files.write(archive.resolve("database.payload"), row.payload(), CREATE_NEW);
            Files.writeString(archive.resolve("decision.txt"), "uuid=" + uuid + "\nrevision=" + row.revision()
                + "\nhash=" + row.payloadHash() + "\nchoice=" + (acceptCurrent ? "accept-current" : "exact-duplicate") + "\n", CREATE_NEW);
            // Keep both inventories before releasing SQL ownership. Failure leaves the conflict retryable.
            Files.copy(conflict, archive.resolve("backup.conflict"));
            if (releaseOwner && !repository.releaseReviewedConflict(row)) {
                throw new IOException("Session or database snapshot changed during review; retry recovery");
            }
            Files.move(conflict, archive.resolve("original.conflict"), StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
    }

    public Entry readConflict(UUID uuid) throws IOException {
        return readFile(directory.resolve(uuid + ".conflict"), uuid)
            .orElseThrow(() -> new NoSuchFileException("No conflict journal for " + uuid));
    }

    public boolean archiveConflict(UUID uuid) throws Exception {
        try (PlayerLock ignored = lock(uuid)) {
            Path conflict = directory.resolve(uuid + ".conflict");
            if (!Files.exists(conflict)) return true;
            Entry entry = readFile(conflict, uuid).orElse(null);
            if (entry == null) return true;
            Path archive = directory.resolve("resolved").resolve(uuid + "-" + UUID.randomUUID());
            Files.createDirectories(archive);
            Files.writeString(archive.resolve("decision.txt"), "uuid=" + uuid + "\nrevision=" + entry.revision()
                + "\nhash=" + entry.hash() + "\nchoice=superseded-auto-archived\n", CREATE_NEW);
            Files.move(conflict, archive.resolve("original.conflict"), StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
    }

    public boolean hasConflict(UUID uuid) {
        return Files.exists(directory.resolve(uuid + ".conflict"));
    }

    /** Called only after this process confirmed SQL commit and ownership release. */
    public void acknowledgeCommitted(Entry committed) throws IOException {
        try (PlayerLock ignored = lock(committed.identity().playerUuid())) {
            UUID uuid = committed.identity().playerUuid();
            if (!contains(uuid)) return;
            Entry pending = readFile(path(uuid), uuid).orElse(null);
            if (pending != null && pending.identity().equals(committed.identity()) && pending.revision() == committed.revision()
                && pending.hash().equals(committed.hash())) Files.deleteIfExists(path(uuid));
        }
    }

    public boolean contains(UUID uuid) {
        return Files.exists(path(uuid));
    }

    /** Idempotent even if SQL committed but the original response was lost. */
    public CommitResult commit(Entry entry) {
        var identity = entry.identity();
        try {
            var current = repository.findMetadata(identity.playerUuid()).orElse(null);
            if (current != null && current.revision() == entry.revision() + 1 && entry.hash().equals(current.payloadHash())) {
                return new CommitResult.Success(current.revision(), 0);
            }
            if (current == null || current.fencingToken() != identity.fencingToken()
                || !identity.sessionId().equals(current.activeSessionId())
                || !identity.serverId().equals(current.ownerServer())) {
                if (repository.getSnapshotRepository() != null) {
                    dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord record =
                        new dev.lyy.friendlysync.database.repository.PlayerSnapshotRecord(
                            UUID.randomUUID().toString(),
                            identity.playerUuid(),
                            entry.revision(),
                            identity.fencingToken(),
                            identity.sessionId(),
                            identity.serverId(),
                            "FINAL_QUIT",
                            "SUPERSEDED",
                            entry.payload(),
                            entry.version(),
                            entry.hash(),
                            java.time.Instant.now()
                        );
                    repository.getSnapshotRepository().insertSnapshot(record);
                }
                String reason = (current != null && !identity.serverId().equals(current.ownerServer()))
                    ? "Journal owner was released" : "Journal session was superseded";
                return new CommitResult.Conflict(
                    current != null ? current.revision() : entry.revision(),
                    current != null ? current.fencingToken() : identity.fencingToken(),
                    reason
                );
            }
            return repository.commit(identity.playerUuid(), identity.sessionId(), identity.fencingToken(),
                entry.revision(), entry.payload(), entry.version(), entry.hash(), "FINAL_QUIT", identity.serverId());
        } catch (Exception e) {
            return new CommitResult.Failed(e);
        }
    }

    /** Only used with no live local session, on startup or by the retry worker. */
    public CommitResult recover(UUID uuid) throws IOException {
        try (PlayerLock ignored = lock(uuid)) {
            Entry entry = readFile(path(uuid), uuid).orElse(null);
            if (entry == null) {
                return new CommitResult.Success(0, 0);
            }
            CommitResult result = commit(entry);
            if (result instanceof CommitResult.Success) {
                try {
                    var identity = entry.identity();
                    var current = repository.findMetadata(uuid).orElse(null);
                    boolean alreadyOffline = current != null && current.ownerServer() == null;
                    if (alreadyOffline || repository.releaseOwnership(uuid, identity.sessionId(), identity.fencingToken(), "OFFLINE")) {
                        Files.deleteIfExists(path(uuid));
                    } else {
                        return new CommitResult.Failed(new IOException("Recovery ownership release was rejected"));
                    }
                } catch (Exception e) {
                    return new CommitResult.Failed(e);
                }
            }
            if (result instanceof CommitResult.Conflict) {
                // Terminal conflicts cannot be retried with this identity. Preserve the exact
                // snapshot for manual recovery, outside the automatic replay/stop queue.
                Path conflict = directory.resolve(uuid + ".conflict");
                // ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING.
                // Preserve both records if an unresolved conflict is already present.
                if (Files.exists(conflict)) throw new IOException("Existing conflict must be resolved before archiving another snapshot: " + conflict);
                if (Files.exists(path(uuid))) {
                    Files.move(path(uuid), conflict, StandardCopyOption.ATOMIC_MOVE);
                }
            }
            // Never rebase an old inventory onto a newer session.
            return result;
        }
    }

    public void acknowledge(UUID uuid) throws Exception {
        try (PlayerLock ignored = lock(uuid)) {
            if (!contains(uuid)) return;
            Entry entry = readFile(path(uuid), uuid).orElse(null);
            if (entry == null) return;
            var row = repository.find(uuid).orElse(null);
            if (row != null && row.ownerServer() == null
                && row.fencingToken() == entry.identity().fencingToken()
                && entry.identity().sessionId().equals(row.activeSessionId())
                && row.revision() == entry.revision() + 1 && entry.hash().equals(row.payloadHash())) {
                Files.deleteIfExists(path(uuid));
            }
        }
    }

    private PlayerLock lock(UUID uuid) {
        PlayerLock selected = locks.compute(uuid, (key, current) -> {
            if (current == null) current = new PlayerLock(uuid);
            current.users++;
            return current;
        });
        selected.lock.lock();
        return selected;
    }

    private final class PlayerLock implements AutoCloseable {
        private final UUID uuid;
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private int users; // Only touched inside ConcurrentHashMap.compute.

        private PlayerLock(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public void close() {
            lock.unlock();
            locks.compute(uuid, (key, current) -> {
                if (current != this) throw new IllegalStateException("Journal lock changed while held");
                return --users == 0 ? null : this;
            });
        }
    }

    private Path path(UUID uuid) { return directory.resolve(uuid + ".pending"); }
}
