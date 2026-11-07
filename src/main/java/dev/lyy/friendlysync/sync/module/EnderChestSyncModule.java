package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.EnderChestSnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronizes player ender chest contents.
 */
public final class EnderChestSyncModule implements SyncModule<EnderChestSnapshot> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnderChestSyncModule.class);

    @Override
    public String id() {
        return "ender-chest";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public EnderChestSnapshot capture(Player player) {
        Inventory ec = player.getEnderChest();
        ItemStack[] contents = ec.getContents();
        int size = Math.max(27, ec.getSize());
        byte[][] itemBytes = new byte[size][];

        for (int i = 0; i < Math.min(size, contents.length); i++) {
            ItemStack item = contents[i];
            if (hasItem(item)) {
                itemBytes[i] = item.serializeAsBytes();
            }
        }

        return new EnderChestSnapshot(itemBytes);
    }

    public static ItemStack decodeSingleItem(byte[] b, int slotIndex) {
        if (b == null || b.length == 0) {
            return null;
        }
        ItemStack item = null;
        boolean decodedSuccessfully = false;
        if (b.length >= 2 && (b[0] & 0xFF) == 0xAC && (b[1] & 0xFF) == 0xED) {
            try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(b))) {
                Object obj = bois.readObject();
                if (obj instanceof ItemStack stack) {
                    item = stack;
                    decodedSuccessfully = true;
                } else if (obj == null) {
                    item = null;
                    decodedSuccessfully = true;
                }
            } catch (Throwable ignored) {}
        }
        if (!decodedSuccessfully) {
            try {
                item = ItemStack.deserializeBytes(b);
                decodedSuccessfully = true;
            } catch (Throwable ignored) {
                try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(b))) {
                    Object obj = bois.readObject();
                    if (obj instanceof ItemStack stack) {
                        item = stack;
                        decodedSuccessfully = true;
                    } else if (obj == null) {
                        item = null;
                        decodedSuccessfully = true;
                    }
                } catch (Throwable ignored2) {}
            }
        }
        if (!decodedSuccessfully) {
            throw new IllegalStateException("Cannot decode saved ender chest item at slot " + slotIndex);
        }
        return item;
    }

    public ItemStack[] preDecode(EnderChestSnapshot data) {
        if (data == null) {
            return null;
        }
        byte[][] itemBytes = data.itemsRaw();
        int snapshotSize = itemBytes != null ? Math.max(27, itemBytes.length) : 27;
        ItemStack[] decoded = new ItemStack[snapshotSize];

        if (itemBytes != null) {
            for (int i = 0; i < itemBytes.length; i++) {
                byte[] b = itemBytes[i];
                if (b != null && b.length > 0) {
                    decoded[i] = decodeSingleItem(b, i);
                }
            }
        }
        return decoded;
    }

    @Override
    public void apply(Player player, EnderChestSnapshot data) {
        apply(player, data, null);
    }

    public void apply(Player player, EnderChestSnapshot data, ItemStack[] preDecoded) {
        if (data == null) {
            return;
        }

        int snapshotSize = (preDecoded != null)
            ? preDecoded.length
            : (data.itemsRaw() != null ? Math.max(27, data.itemsRaw().length) : 27);

        ItemStack[] decoded;
        if (preDecoded != null) {
            decoded = preDecoded;
        } else {
            byte[][] itemBytes = data.itemsRaw();
            decoded = new ItemStack[snapshotSize];
            if (itemBytes != null) {
                for (int i = 0; i < itemBytes.length; i++) {
                    byte[] b = itemBytes[i];
                    if (b != null && b.length > 0) {
                        decoded[i] = decodeSingleItem(b, i);
                    }
                }
            }
        }

        // Only after all slots have safely decoded without errors, access live inventory
        Inventory ec = player.getEnderChest();
        int targetSize = ec.getSize() > 0 ? ec.getSize() : snapshotSize;
        ItemStack[] finalItems = (targetSize == snapshotSize) ? decoded : new ItemStack[targetSize];
        if (targetSize != snapshotSize) {
            System.arraycopy(decoded, 0, finalItems, 0, Math.min(snapshotSize, targetSize));
        }
        ec.setContents(finalItems);
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("ender-chest");
    }

    private static boolean isAir(Material mat) {
        if (mat == null) return false;
        try {
            return mat.isAir();
        } catch (Throwable ignored) {
            try {
                return mat.name().contains("AIR");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static boolean hasItem(ItemStack item) {
        if (item == null) return false;
        try {
            if (item.isEmpty()) return false;
        } catch (Throwable ignored) {}
        try {
            Material type = item.getType();
            if (type != null && isAir(type)) return false;
        } catch (Throwable ignored) {}
        return true;
    }
}
