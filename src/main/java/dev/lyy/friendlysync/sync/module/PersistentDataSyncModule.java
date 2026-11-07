package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import dev.lyy.friendlysync.sync.pdc.PersistentDataApplyService;
import dev.lyy.friendlysync.sync.pdc.PersistentDataCaptureService;
import dev.lyy.friendlysync.sync.pdc.PersistentDataPolicy;
import dev.lyy.friendlysync.util.Preconditions;
import org.bukkit.entity.Player;

/**
 * Synchronization module for player PersistentDataContainer.
 */
public final class PersistentDataSyncModule implements SyncModule<PersistentDataSnapshot> {

    private final PersistentDataPolicy policy;
    private final PersistentDataCaptureService captureService;
    private final PersistentDataApplyService applyService;

    public PersistentDataSyncModule(PersistentDataPolicy policy) {
        this.policy = Preconditions.checkNotNull(policy, "policy cannot be null");
        this.captureService = new PersistentDataCaptureService(policy);
        this.applyService = new PersistentDataApplyService(policy);
    }

    @Override
    public String id() {
        return "persistent_data";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public PersistentDataSnapshot capture(Player player) {
        return captureService.capture(player);
    }

    @Override
    public void apply(Player player, PersistentDataSnapshot data) {
        applyService.apply(player, data);
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isPdcEnabled();
    }

    public PersistentDataPolicy policy() {
        return policy;
    }
}
