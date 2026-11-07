package dev.lyy.friendlysync.sync.module;

import dev.lyy.friendlysync.config.FriendlySyncConfig;
import dev.lyy.friendlysync.model.InventorySnapshot;
import dev.lyy.friendlysync.sync.SyncModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronizes player inventory contents including storage, armor, offhand, cursor, and held slot.
 */
public final class InventorySyncModule implements SyncModule<InventorySnapshot> {

    private static final Logger LOGGER = LoggerFactory.getLogger(InventorySyncModule.class);

    @Override
    public String id() {
        return "inventory";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public InventorySnapshot capture(Player player) {
        // If the player is dead and keepInventory is false, all items have dropped on this server.
        // Capturing live inventory might still see items before CraftBukkit clears them, or
        // cause a dupe if another plugin restored items or player transferred while dead. Force empty inventory.
        org.bukkit.World world = player.getWorld();
        boolean keepInventory = (world != null && Boolean.TRUE.equals(world.getGameRuleValue(org.bukkit.GameRule.KEEP_INVENTORY)))
            || dev.lyy.friendlysync.listener.PlayerDeathListener.hasKeepInventory(player.getUniqueId());
        if (player.isDead() && !keepInventory) {
            boolean hasItems = false;
            PlayerInventory inv = player.getInventory();
            if (inv != null) {
                ItemStack[] storage = inv.getStorageContents();
                if (storage != null) {
                    for (ItemStack item : storage) {
                        if (hasItem(item)) {
                            hasItems = true;
                            break;
                        }
                    }
                }
                if (!hasItems) {
                    ItemStack[] armor = inv.getArmorContents();
                    if (armor != null) {
                        for (ItemStack item : armor) {
                            if (hasItem(item)) {
                                hasItems = true;
                                break;
                            }
                        }
                    }
                }
                if (!hasItems && hasItem(inv.getItemInOffHand())) {
                    hasItems = true;
                }
            }
            if (!hasItems) {
                return new InventorySnapshot(new byte[36][], new byte[4][], null, 0, null);
            }
        }

        PlayerInventory inv = player.getInventory();

        ItemStack[] storageContents = inv.getStorageContents();
        byte[][] storageBytes = new byte[36][];
        for (int i = 0; i < Math.min(36, storageContents.length); i++) {
            ItemStack item = storageContents[i];
            storageBytes[i] = serializeItem(item);
        }

        ItemStack[] armorContents = inv.getArmorContents();
        byte[][] armorBytes = new byte[4][];
        for (int i = 0; i < Math.min(4, armorContents.length); i++) {
            ItemStack item = armorContents[i];
            armorBytes[i] = serializeItem(item);
        }

        byte[] offHandBytes = serializeItem(inv.getItemInOffHand());
        byte[] cursorBytes = serializeItem(player.getItemOnCursor());
        int heldSlot = inv.getHeldItemSlot();

        return new InventorySnapshot(storageBytes, armorBytes, offHandBytes, heldSlot, cursorBytes);
    }

    public record DecodedInventory(
        ItemStack[] storage,
        ItemStack[] armor,
        ItemStack offHand,
        ItemStack cursor
    ) {}

    public DecodedInventory preDecode(InventorySnapshot data) {
        if (data == null) {
            return null;
        }
        byte[][] storageBytes = data.storageRaw();
        ItemStack[] storage = new ItemStack[36];
        if (storageBytes != null) {
            for (int i = 0; i < Math.min(36, storageBytes.length); i++) {
                storage[i] = deserializeItem(storageBytes[i]);
            }
        }

        byte[][] armorBytes = data.armorRaw();
        ItemStack[] armor = new ItemStack[4];
        if (armorBytes != null) {
            for (int i = 0; i < Math.min(4, armorBytes.length); i++) {
                armor[i] = deserializeItem(armorBytes[i]);
            }
        }
        ItemStack offHand = deserializeItem(data.offHandRaw());
        ItemStack cursor = deserializeItem(data.cursorRaw());
        return new DecodedInventory(storage, armor, offHand, cursor);
    }

    @Override
    public void apply(Player player, InventorySnapshot data) {
        apply(player, data, null, true);
    }

    public void apply(Player player, InventorySnapshot data, DecodedInventory preDecoded) {
        apply(player, data, preDecoded, true);
    }

    @SuppressWarnings("deprecation")
    public void apply(Player player, InventorySnapshot data, DecodedInventory preDecoded, boolean updateClient) {
        if (data == null) {
            return;
        }

        PlayerInventory inv = player.getInventory();

        ItemStack[] storage;
        ItemStack[] armor;
        ItemStack offHand;
        ItemStack cursor;

        if (preDecoded != null) {
            storage = preDecoded.storage();
            armor = preDecoded.armor();
            offHand = preDecoded.offHand();
            cursor = preDecoded.cursor();
        } else {
            byte[][] storageBytes = data.storageRaw();
            storage = new ItemStack[36];
            if (storageBytes != null) {
                for (int i = 0; i < Math.min(36, storageBytes.length); i++) {
                    storage[i] = deserializeItem(storageBytes[i]);
                }
            }

            byte[][] armorBytes = data.armorRaw();
            armor = new ItemStack[4];
            if (armorBytes != null) {
                for (int i = 0; i < Math.min(4, armorBytes.length); i++) {
                    armor[i] = deserializeItem(armorBytes[i]);
                }
            }
            offHand = deserializeItem(data.offHandRaw());
            cursor = deserializeItem(data.cursorRaw());
        }

        // Decode every slot before mutating the live inventory.
        inv.setStorageContents(storage);
        inv.setArmorContents(armor);

        inv.setItemInOffHand(offHand);
        if (hasItem(cursor)) {
            java.util.HashMap<Integer, ItemStack> leftover = inv.addItem(cursor);
            if (!leftover.isEmpty()) {
                for (ItemStack rem : leftover.values()) {
                    if (player.getEnderChest() != null) {
                        java.util.HashMap<Integer, ItemStack> ecLeftover = player.getEnderChest().addItem(rem);
                        for (ItemStack drop : ecLeftover.values()) {
                            if (hasItem(drop) && player.getWorld() != null && player.getLocation() != null) {
                                player.getWorld().dropItemNaturally(player.getLocation(), drop);
                            }
                        }
                    } else if (hasItem(rem) && player.getWorld() != null && player.getLocation() != null) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rem);
                    }
                }
            }
            player.setItemOnCursor(null);
        } else {
            player.setItemOnCursor(null);
        }

        int heldSlot = Math.max(0, Math.min(8, data.heldSlot()));
        inv.setHeldItemSlot(heldSlot);

        if (updateClient) {
            player.updateInventory();
        }
    }

    @Override
    public boolean isEnabled(FriendlySyncConfig config) {
        return config.isModuleEnabled("inventory");
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

    private static byte[] serializeItem(ItemStack item) {
        if (!hasItem(item)) {
            return null;
        }
        return item.serializeAsBytes();
    }

    @SuppressWarnings("deprecation")
    private static ItemStack deserializeItem(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xAC && (bytes[1] & 0xFF) == 0xED) {
            try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                Object obj = bois.readObject();
                if (obj instanceof ItemStack stack) {
                    return stack;
                } else if (obj == null) {
                    return null;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot decode legacy saved inventory item; refusing to replace it with an empty slot", e);
            }
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            try (org.bukkit.util.io.BukkitObjectInputStream bois = new org.bukkit.util.io.BukkitObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                Object obj = bois.readObject();
                if (obj instanceof ItemStack stack) {
                    return stack;
                } else if (obj == null) {
                    return null;
                }
            } catch (Throwable ignored) {}
            throw new IllegalStateException("Cannot decode saved inventory item; refusing to replace it with an empty slot", e);
        }
    }
}
