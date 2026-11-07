package dev.lyy.friendlysync.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings an existing config.yml up to date at startup: drops sections of removed features and
 * writes new keys with their comments. A written key always holds the value the plugin already
 * used for it, so updating never changes behavior. Unreadable YAML is left untouched.
 */
public final class ConfigUpdater {

    /** Sections of removed features; nothing reads them anymore. */
    static final List<String> REMOVED = List.of("end-portal", "auto-restart", "migration", "world-mappings");

    /**
     * Never written, because the bundled value differs from what an absent key means:
     * a module missing from sync.modules counts as enabled, and an
     * absent redis.database / redis.timeout means 0 / 3000, not the bundled 1 / 2000.
     * ConfigUpdaterTest fails when another key needs to join this list.
     */
    static final List<String> KEEP_UNSET = List.of("sync.modules", "redis.database", "redis.timeout");

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ConfigUpdater() {
    }

    /** Returns the changes written, empty when the file was already current or could not be parsed. */
    public static List<String> update(Path file, YamlConfiguration defaults) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
        } catch (InvalidConfigurationException e) {
            return List.of();
        }

        List<String> changes = new ArrayList<>();
        for (String path : REMOVED) {
            if (config.contains(path)) {
                config.set(path, null);
                changes.add("removed " + path);
            }
        }
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.contains(key)
                || keptUnset(key) || hasNonSectionAncestor(config, key)) {
                continue;
            }
            List<String> newSections = new ArrayList<>();
            for (int dot = key.indexOf('.'); dot > 0; dot = key.indexOf('.', dot + 1)) {
                if (!config.contains(key.substring(0, dot))) newSections.add(key.substring(0, dot));
            }
            config.set(key, defaults.get(key));
            for (String section : newSections) {
                config.setComments(section, defaults.getComments(section));
            }
            config.setComments(key, defaults.getComments(key));
            config.setInlineComments(key, defaults.getInlineComments(key));
            changes.add("added " + key);
        }
        if (changes.isEmpty()) {
            return changes;
        }

        Path backup = file.resolveSibling(file.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_STAMP));
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, config.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        changes.add("backup " + backup.getFileName());
        return changes;
    }

    private static boolean keptUnset(String key) {
        for (String path : KEEP_UNSET) {
            if (key.equals(path) || key.startsWith(path + ".")) return true;
        }
        return false;
    }

    /** True when the admin replaced a parent section with a plain value; adding children would overwrite it. */
    private static boolean hasNonSectionAncestor(YamlConfiguration config, String key) {
        for (int dot = key.indexOf('.'); dot > 0; dot = key.indexOf('.', dot + 1)) {
            String parent = key.substring(0, dot);
            if (config.contains(parent) && !config.isConfigurationSection(parent)) return true;
        }
        return false;
    }
}
