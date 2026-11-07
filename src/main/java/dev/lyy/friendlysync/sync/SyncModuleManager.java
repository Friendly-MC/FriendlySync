package dev.lyy.friendlysync.sync;

import dev.lyy.friendlysync.api.CustomSyncModule;
import dev.lyy.friendlysync.api.SyncReason;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.EffectSnapshot;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.model.ExperienceSnapshot;
import dev.lyy.friendlysync.model.FlightSnapshot;
import dev.lyy.friendlysync.model.GameModeSnapshot;
import dev.lyy.friendlysync.model.HungerSnapshot;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.model.LocationSnapshot;
import dev.lyy.friendlysync.model.PlayerStateSnapshot;
import dev.lyy.friendlysync.model.ActivePlayerSession;
import dev.lyy.friendlysync.model.VitalSnapshot;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.model.stats.StatisticsSnapshot;
import dev.lyy.friendlysync.sync.module.EnderChestSyncModule;
import dev.lyy.friendlysync.sync.module.ExperienceSyncModule;
import dev.lyy.friendlysync.sync.module.FlightSyncModule;
import dev.lyy.friendlysync.sync.module.GameModeSyncModule;
import dev.lyy.friendlysync.sync.module.HungerSyncModule;
import dev.lyy.friendlysync.sync.module.InventorySyncModule;
import dev.lyy.friendlysync.sync.module.LocationSyncModule;
import dev.lyy.friendlysync.sync.module.PersistentDataSyncModule;
import dev.lyy.friendlysync.sync.module.PotionEffectsSyncModule;
import dev.lyy.friendlysync.sync.module.StatisticsSyncModule;
import dev.lyy.friendlysync.sync.module.VitalsSyncModule;
import dev.lyy.friendlysync.sync.stats.StatisticsRegistry;
import dev.lyy.friendlysync.sync.stats.StatisticsCapturePolicy;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and orchestrator for all built-in and external synchronization modules.
 */
public final class SyncModuleManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncModuleManager.class);

    private final FriendlySyncConfig config;

    private final InventorySyncModule inventoryModule;
    private final EnderChestSyncModule enderChestModule;
    private final ExperienceSyncModule experienceModule;
    private final VitalsSyncModule vitalsModule;
    private final HungerSyncModule hungerModule;
    private final PotionEffectsSyncModule potionEffectsModule;
    private final GameModeSyncModule gameModeModule;
    private final FlightSyncModule flightModule;
    private final LocationSyncModule locationModule;
    private final PersistentDataSyncModule persistentDataModule;
    private final StatisticsSyncModule statisticsModule;

    private final Map<String, CustomSyncModule<?>> customModules = new ConcurrentHashMap<>();
    private dev.lyy.friendlysync.session.PlayerSessionService sessionService;

    public void setSessionService(dev.lyy.friendlysync.session.PlayerSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public boolean isLocationTeleportPending(java.util.UUID uuid) {
        return locationModule.isTeleportPending(uuid);
    }

    public SyncModuleManager(FriendlySyncConfig config) {
        this.config = Preconditions.checkNotNull(config, "config cannot be null");

        this.inventoryModule = new InventorySyncModule();
        this.enderChestModule = new EnderChestSyncModule();
        this.experienceModule = new ExperienceSyncModule();
        this.vitalsModule = new VitalsSyncModule();
        this.hungerModule = new HungerSyncModule();
        this.potionEffectsModule = new PotionEffectsSyncModule();
        this.gameModeModule = new GameModeSyncModule();
        this.flightModule = new FlightSyncModule();
        this.locationModule = new LocationSyncModule();
        this.persistentDataModule = new PersistentDataSyncModule(config.getPdcPolicy());
        this.statisticsModule = new StatisticsSyncModule(new StatisticsRegistry());
    }

    public void registerCustomModule(CustomSyncModule<?> module) {
        Preconditions.checkNotNull(module, "module cannot be null");
        customModules.put(module.id(), module);
        LOGGER.info("Registered custom sync module: {} (v{})", module.id(), module.version());
    }

    /**
     * Captures an immutable PlayerStateSnapshot from the given live player.
     * MUST be called on the player's owning thread.
     */
    public PlayerStateSnapshot captureSnapshot(Player player, long revision, long fencingToken) {
        return captureSnapshot(player, revision, fencingToken, null);
    }

    public PlayerStateSnapshot captureSnapshot(Player player, long revision, long fencingToken, SyncReason reason) {
        return captureSnapshot(player, revision, fencingToken, reason, null);
    }

    public PlayerStateSnapshot captureSnapshot(
        Player player,
        long revision,
        long fencingToken,
        SyncReason reason,
        ActivePlayerSession session
    ) {
        Preconditions.checkNotNull(player, "player cannot be null");

        ActivePlayerSession activeSession = session;
        if (activeSession == null && sessionService != null) {
            activeSession = sessionService.getLocalSessionRaw(player.getUniqueId());
        }

        InventorySnapshot inv;
        if (inventoryModule.isEnabled(config)) {
            if (reason == SyncReason.CHECKPOINT && activeSession != null && !activeSession.isInventoryDirty() && activeSession.getCachedInventory().isPresent()) {
                inv = activeSession.getCachedInventory().get();
            } else {
                inv = inventoryModule.capture(player);
                if (activeSession != null && inv != null) {
                    activeSession.cacheInventory(inv);
                }
            }
        } else {
            inv = activeSession != null ? activeSession.getCachedInventory().orElse(null) : null;
        }
        EnderChestSnapshot ec;
        if (enderChestModule.isEnabled(config)) {
            if (reason == SyncReason.CHECKPOINT && activeSession != null && !activeSession.isEnderChestDirty() && activeSession.getCachedEnderChest().isPresent()) {
                ec = activeSession.getCachedEnderChest().get();
            } else {
                ec = enderChestModule.capture(player);
                if (activeSession != null && ec != null) {
                    activeSession.cacheEnderChest(ec);
                }
            }
        } else {
            ec = activeSession != null ? activeSession.getCachedEnderChest().orElse(null) : null;
        }
        ExperienceSnapshot xp = experienceModule.isEnabled(config) ? experienceModule.capture(player) : null;
        VitalSnapshot vitals = vitalsModule.isEnabled(config) ? vitalsModule.capture(player) : null;
        HungerSnapshot hunger = hungerModule.isEnabled(config) ? hungerModule.capture(player) : null;
        List<EffectSnapshot> effects = potionEffectsModule.isEnabled(config) ? potionEffectsModule.capture(player) : List.of();
        GameModeSnapshot gm = gameModeModule.isEnabled(config) ? gameModeModule.capture(player) : null;
        FlightSnapshot flight = flightModule.isEnabled(config) ? flightModule.capture(player) : null;
        LocationSnapshot loc = locationModule.isEnabled(config) ? locationModule.capture(player) : null;
        PersistentDataSnapshot pdc = persistentDataModule.isEnabled(config) ? persistentDataModule.capture(player) : PersistentDataSnapshot.EMPTY;
        StatisticsSnapshot stats = captureStatistics(player, reason, activeSession);

        Map<String, byte[]> customData = new HashMap<>();
        for (Map.Entry<String, CustomSyncModule<?>> entry : customModules.entrySet()) {
            try {
                @SuppressWarnings("unchecked")
                CustomSyncModule<Object> cMod = (CustomSyncModule<Object>) entry.getValue();
                Object data = cMod.capture(player);
                if (data != null) {
                    byte[] serialized = cMod.serialize(data);
                    customData.put(entry.getKey(), serialized);
                }
            } catch (Exception e) {
                LOGGER.error("Error capturing custom module {}: {}", entry.getKey(), e.getMessage(), e);
            }
        }

        return new PlayerStateSnapshot(
            player.getUniqueId(),
            revision,
            fencingToken,
            inv,
            ec,
            xp,
            vitals,
            hunger,
            effects,
            gm,
            flight,
            loc,
            pdc,
            stats,
            customData,
            Instant.now(),
            PlayerStateSnapshot.CURRENT_SCHEMA_VERSION
        );
    }

    private StatisticsSnapshot captureStatistics(
        Player player,
        SyncReason reason,
        ActivePlayerSession session
    ) {
        if (!statisticsModule.isEnabled(config)) {
            return StatisticsSnapshot.EMPTY;
        }

        ActivePlayerSession.CachedStatistics cached = session != null
            ? session.getCachedStatistics().orElse(null)
            : null;

        boolean refresh;
        if (reason == null) {
            // Preserve the original overload's always-fresh behavior.
            refresh = true;
        } else if (reason == SyncReason.CHECKPOINT) {
            refresh = config.isStatisticsCaptureOnCheckpoint()
                && StatisticsCapturePolicy.checkpointRefreshDue(
                    cached != null ? cached.capturedAt() : null,
                    config.getCheckpointInterval(),
                    Instant.now()
                );
        } else {
            // Transfers, quits, shutdowns, and explicit saves remain exact boundary captures.
            refresh = config.isStatisticsCaptureOnFinalSave();
        }

        // A missing cache must never become an empty persisted snapshot merely to save CPU.
        if (refresh || cached == null) {
            StatisticsSnapshot captured = statisticsModule.capture(player);
            if (session != null) {
                session.cacheStatistics(captured, Instant.now());
            }
            return captured;
        }
        return cached.snapshot();
    }

    public InventorySyncModule.DecodedInventory preDecodeInventory(InventorySnapshot inv) {
        return inventoryModule.preDecode(inv);
    }

    public org.bukkit.inventory.ItemStack[] preDecodeEnderChest(EnderChestSnapshot ec) {
        return enderChestModule.preDecode(ec);
    }

    /**
     * Applies an immutable PlayerStateSnapshot to the live player with deterministic sequencing.
     * MUST be called on the player's owning thread.
     */
    public void applySnapshot(Player player, PlayerStateSnapshot snapshot) {
        applySnapshot(player, snapshot, null, null);
    }

    public void applySnapshot(Player player, PlayerStateSnapshot snapshot, ActivePlayerSession session) {
        applySnapshot(player, snapshot, session, null, null);
    }

    public void applySnapshot(
        Player player,
        PlayerStateSnapshot snapshot,
        ActivePlayerSession session,
        InventorySyncModule.DecodedInventory decodedInv,
        org.bukkit.inventory.ItemStack[] decodedEc
    ) {
        if (snapshot != null && session != null) {
            if (snapshot.schemaVersion() >= 3 && snapshot.statistics() != null) {
                session.cacheStatistics(snapshot.statistics(), snapshot.capturedAt());
            }
            if (snapshot.enderChest() != null) {
                session.cacheEnderChest(snapshot.enderChest());
            }
            if (snapshot.inventory() != null) {
                session.cacheInventory(snapshot.inventory());
            }
        }
        boolean updateClient = (session == null || session.getState() == dev.lyy.friendlysync.model.SessionState.ACTIVE);
        applySnapshot(player, snapshot, decodedInv, decodedEc, updateClient);
        if (session != null && snapshot != null && snapshot.inventory() != null
            && snapshot.inventory().cursorRaw() != null && snapshot.inventory().cursorRaw().length > 0
            && inventoryModule.isEnabled(config)) {
            // Cursor recovery changes the saved slot layout; never reuse the pre-apply item cache.
            session.markInventoryDirty();
            if (enderChestModule.isEnabled(config)) session.markEnderChestDirty();
            session.markDirty();
        }
    }

    public void applySnapshot(
        Player player,
        PlayerStateSnapshot snapshot,
        InventorySyncModule.DecodedInventory decodedInv,
        org.bukkit.inventory.ItemStack[] decodedEc
    ) {
        applySnapshot(player, snapshot, decodedInv, decodedEc, true);
    }

    public void applySnapshot(
        Player player,
        PlayerStateSnapshot snapshot,
        InventorySyncModule.DecodedInventory decodedInv,
        org.bukkit.inventory.ItemStack[] decodedEc,
        boolean updateClient
    ) {
        Preconditions.checkNotNull(player, "player cannot be null");
        if (snapshot == null) {
            return;
        }

        // Decode both inventories before changing either. Restoring the chest first
        // lets inventory cursor recovery safely place overflow into its final contents.
        if (snapshot.inventory() != null && inventoryModule.isEnabled(config) && decodedInv == null) {
            decodedInv = inventoryModule.preDecode(snapshot.inventory());
        }
        if (snapshot.enderChest() != null && enderChestModule.isEnabled(config) && decodedEc == null) {
            decodedEc = enderChestModule.preDecode(snapshot.enderChest());
        }

        if (snapshot.gameMode() != null && gameModeModule.isEnabled(config)) {
            gameModeModule.apply(player, snapshot.gameMode());
        }

        if (snapshot.flight() != null && flightModule.isEnabled(config)) {
            flightModule.apply(player, snapshot.flight());
        }

        if (snapshot.enderChest() != null && enderChestModule.isEnabled(config)) {
            enderChestModule.apply(player, snapshot.enderChest(), decodedEc);
        }

        // 3. Inventory, including cursor recovery after the chest has been restored
        if (snapshot.inventory() != null && inventoryModule.isEnabled(config)) {
            inventoryModule.apply(player, snapshot.inventory(), decodedInv, updateClient);
        }

        if (snapshot.persistentData() != null && persistentDataModule.isEnabled(config)) {
            persistentDataModule.apply(player, snapshot.persistentData());
        }

        if (snapshot.schemaVersion() >= 3 && snapshot.statistics() != null && statisticsModule.isEnabled(config)) {
            statisticsModule.apply(player, snapshot.statistics());
        }

        if (snapshot.experience() != null && experienceModule.isEnabled(config)) {
            experienceModule.apply(player, snapshot.experience());
        }

        if (snapshot.vitals() != null && vitalsModule.isEnabled(config)) {
            vitalsModule.apply(player, snapshot.vitals());
        }

        if (snapshot.hunger() != null && hungerModule.isEnabled(config)) {
            hungerModule.apply(player, snapshot.hunger());
        }

        if (snapshot.effects() != null && potionEffectsModule.isEnabled(config)) {
            potionEffectsModule.apply(player, snapshot.effects());
        }

        if (snapshot.location() != null && locationModule.isEnabled(config)) {
            locationModule.apply(player, snapshot.location());
        }

        if (snapshot.customModuleData() != null) {
            for (Map.Entry<String, byte[]> entry : snapshot.customModuleData().entrySet()) {
                CustomSyncModule<?> mod = customModules.get(entry.getKey());
                if (mod != null && entry.getValue() != null) {
                    try {
                        applyCustomData(mod, player, entry.getValue());
                    } catch (Exception e) {
                        LOGGER.error("Error applying custom module {}: {}", entry.getKey(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    private <T> void applyCustomData(CustomSyncModule<T> module, Player player, byte[] bytes) {
        T data = module.deserialize(bytes, module.version());
        if (data != null) {
            module.apply(player, data);
        }
    }

    public PersistentDataSyncModule persistentDataModule() {
        return persistentDataModule;
    }

    public StatisticsSyncModule statisticsModule() {
        return statisticsModule;
    }
}
