package dev.lyy.friendlysync;

import dev.lyy.friendlysync.config.ConfigUpdater;
import dev.lyy.friendlysync.config.FriendlySyncConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigUpdaterTest {

    @TempDir Path directory;

    private static YamlConfiguration bundled() {
        return YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
    }

    @Test
    void upgradeDropsRemovedFeaturesAddsNewKeysAndKeepsAdminChoices() throws Exception {
        YamlConfiguration old = bundled();
        old.set("server.id", "economy-a");
        old.set("database.password", "secret");
        old.set("end-portal.enter-server", "the-end");
        old.set("auto-restart.enabled", true);
        old.set("migration.mc-data-bridge.enabled", true);
        old.set("snapshots", null);
        old.set("sync.modules.location", null);
        old.set("redis.database", null);
        old.set("world-mappings.survival.economy-a", "world_survival"); // unused since location sync uses world names
        Path file = directory.resolve("config.yml");
        Files.writeString(file, old.saveToString());
        String original = Files.readString(file);

        List<String> changes = ConfigUpdater.update(file, bundled());

        YamlConfiguration updated = YamlConfiguration.loadConfiguration(file.toFile());
        assertThat(updated.contains("end-portal")).isFalse();
        assertThat(updated.contains("auto-restart")).isFalse();
        assertThat(updated.contains("migration")).isFalse();
        assertThat(updated.getString("server.id")).isEqualTo("economy-a");
        assertThat(updated.getString("database.password")).isEqualTo("secret");
        assertThat(updated.getBoolean("snapshots.enabled")).isTrue();
        assertThat(updated.getComments("snapshots")).isNotEmpty();
        assertThat(updated.contains("sync.modules.location")).as("a missing module stays enabled").isFalse();
        assertThat(updated.contains("redis.database")).as("absent means database 0, not the bundled 1").isFalse();
        assertThat(updated.contains("world-mappings")).isFalse();
        assertThat(changes).anyMatch(c -> c.startsWith("backup "));
        try (var files = Files.list(directory)) {
            Path backup = files.filter(p -> p.getFileName().toString().startsWith("config.yml.bak-")).findFirst().orElseThrow();
            assertThat(Files.readString(backup)).isEqualTo(original);
        }
        assertThat(ConfigUpdater.update(file, bundled())).as("second start changes nothing").isEmpty();
    }

    @Test
    void unreadableYamlIsLeftUntouched() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "server:\n  id: [unclosed\n");
        assertThat(ConfigUpdater.update(file, bundled())).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("server:\n  id: [unclosed\n");
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    /** A key written by the updater must parse exactly as its absence did, or upgrading would change behavior. */
    @Test
    void everyKeyTheUpdaterCanAddKeepsTheParsedConfiguration() throws Exception {
        YamlConfiguration base = bundled();
        base.set("server.id", "economy-a");
        base.set("database.password", "secret");
        List<String> mismatches = new ArrayList<>();
        for (String key : base.getKeys(true)) {
            if (base.isConfigurationSection(key)) continue;
            YamlConfiguration without = YamlConfiguration.loadConfiguration(new java.io.StringReader(base.saveToString()));
            without.set(key, null);
            Path file = directory.resolve(key + ".yml");
            Files.writeString(file, without.saveToString());
            List<String> changes = ConfigUpdater.update(file, bundled());
            if (!changes.contains("added " + key)) continue;
            String expected = parse(without);
            String actual = parse(YamlConfiguration.loadConfiguration(file.toFile()));
            if (!expected.equals(actual)) mismatches.add(key + "\n  without: " + expected + "\n  added:   " + actual);
        }
        assertThat(mismatches).isEmpty();
    }

    private static String parse(YamlConfiguration yaml) {
        try {
            return describe(new FriendlySyncConfig(yaml)).toString();
        } catch (RuntimeException e) {
            return "rejected: " + e.getClass().getSimpleName();
        }
    }

    private static Map<String, Object> describe(Object value) throws RuntimeException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            try {
                Object fieldValue = field.get(value);
                boolean ours = fieldValue != null && !fieldValue.getClass().isEnum()
                    && fieldValue.getClass().getName().startsWith("dev.lyy.friendlysync");
                fields.put(field.getName(), ours ? describe(fieldValue) : String.valueOf(fieldValue));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return fields;
    }
}
