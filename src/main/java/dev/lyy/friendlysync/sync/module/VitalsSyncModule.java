package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.VitalSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Synchronizes player health, max health, and absorption.
 * Guarantees health is never restored above the currently valid maximum on the target server.
 */
public final class VitalsSyncModule implements SyncModule<VitalSnapshot> {

    private static final Attribute MAX_HEALTH_ATTR = resolveMaxHealthAttribute();

    @SuppressWarnings({"deprecation", "removal"})
    private static Attribute resolveMaxHealthAttribute() {
        try {
            return Attribute.valueOf("MAX_HEALTH"); // 1.21.2+ / 1.21.4 / 1.21.11 / 26.2
        } catch (Throwable e) {
            try {
                return Attribute.valueOf("GENERIC_MAX_HEALTH"); // 1.21.0 / 1.21.1
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    @Override
    public String id() {
        return "health";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public VitalSnapshot capture(Player player) {
        AttributeInstance maxHealthAttr = MAX_HEALTH_ATTR != null ? player.getAttribute(MAX_HEALTH_ATTR) : null;
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
        double health = (player.isDead() || player.getHealth() <= 0.0) ? maxHealth : player.getHealth();
        return new VitalSnapshot(
            health,
            maxHealth,
            player.isDead() ? 0.0 : player.getAbsorptionAmount()
        );
    }

    @Override
    public void apply(Player player, VitalSnapshot data) {
        if (data == null || player.isDead()) {
            return;
        }

        AttributeInstance maxHealthAttr = MAX_HEALTH_ATTR != null ? player.getAttribute(MAX_HEALTH_ATTR) : null;
        double currentMax = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

        // Never restore health above current valid maximum
        double targetHealth = Math.max(0.5, Math.min(currentMax, data.health()));
        player.setHealth(targetHealth);
        player.setAbsorptionAmount(Math.max(0.0, data.absorption()));
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("health");
    }
}
