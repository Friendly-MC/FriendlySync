package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.EffectSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Synchronizes active player potion effects.
 */
public final class PotionEffectsSyncModule implements SyncModule<List<EffectSnapshot>> {

    @Override
    public String id() {
        return "potion-effects";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<EffectSnapshot> capture(Player player) {
        if (player.isDead()) {
            return List.of();
        }

        List<EffectSnapshot> list = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            list.add(new EffectSnapshot(
                effect.getType().getKey().toString(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon()
            ));
        }
        return list;
    }

    @Override
    public void apply(Player player, List<EffectSnapshot> data) {
        if (data == null) {
            return;
        }

        for (PotionEffect active : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(active.getType());
        }

        for (EffectSnapshot snap : data) {
            NamespacedKey key = NamespacedKey.fromString(snap.type());
            PotionEffectType type = key != null ? Registry.POTION_EFFECT_TYPE.get(key) : null;
            if (type != null && snap.duration() > 0) {
                player.addPotionEffect(new PotionEffect(
                    type,
                    snap.duration(),
                    snap.amplifier(),
                    snap.ambient(),
                    snap.particles(),
                    snap.icon()
                ));
            }
        }
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("potion-effects");
    }
}
