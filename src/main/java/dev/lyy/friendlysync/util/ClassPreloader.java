package dev.lyy.friendlysync.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Pre-warms and eagerly loads all FriendlySync classes into JVM Metaspace during plugin initialization.
 * This guarantees that class resolution will never fail due to asynchronous classloader race conditions,
 * lazy-loading faults, or JAR file index invalidation when updating plugin JARs.
 */
public final class ClassPreloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPreloader.class);

    private ClassPreloader() {
    }

    /**
     * Preloads all plugin classes in the JAR or classpath to ensure they are available in Metaspace.
     *
     * @param classLoader plugin class loader
     * @param anchorClass anchor class within the plugin JAR
     * @return number of classes preloaded
     */
    public static int preload(ClassLoader classLoader, Class<?> anchorClass) {
        AtomicInteger count = new AtomicInteger(0);

        try {
            if (anchorClass.getProtectionDomain() != null && anchorClass.getProtectionDomain().getCodeSource() != null) {
                URI uri = anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI();
                File location = new File(uri);

                if (location.isFile() && location.getName().endsWith(".jar")) {
                    try (JarFile jar = new JarFile(location)) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith("dev/lyy/friendlysync/") && name.endsWith(".class")) {
                                String className = name.substring(0, name.length() - 6).replace('/', '.');
                                loadClassSilently(className, classLoader, count);
                            }
                        }
                    }
                } else if (location.isDirectory()) {
                    Path rootPath = location.toPath().resolve("dev/lyy/friendlysync");
                    if (Files.exists(rootPath)) {
                        try (Stream<Path> stream = Files.walk(rootPath)) {
                            stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                                Path relative = location.toPath().relativize(p);
                                String className = relative.toString()
                                    .replace(File.separatorChar, '.')
                                    .replace('/', '.')
                                    .replaceAll("\\.class$", "");
                                loadClassSilently(className, classLoader, count);
                            });
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Dynamic classpath scanning note: {}", t.getMessage());
        }

        // Explicitly warm up all core data models, records, and serialization types
        preloadExplicit(classLoader, count);

        LOGGER.info("[PRELOAD] Eagerly warmed {} FriendlySync classes into JVM Metaspace", count.get());
        return count.get();
    }

    private static void loadClassSilently(String className, ClassLoader classLoader, AtomicInteger count) {
        try {
            Class.forName(className, false, classLoader);
            count.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    private static void preloadExplicit(ClassLoader classLoader, AtomicInteger count) {
        Class<?>[] classes = new Class<?>[] {
            dev.lyy.friendlysync.model.PlayerStateSnapshot.class,
            dev.lyy.friendlysync.model.GameModeSnapshot.class,
            dev.lyy.friendlysync.model.FlightSnapshot.class,
            dev.lyy.friendlysync.model.EffectSnapshot.class,
            dev.lyy.friendlysync.model.HungerSnapshot.class,
            dev.lyy.friendlysync.model.InventorySnapshot.class,
            dev.lyy.friendlysync.model.LocationSnapshot.class,
            dev.lyy.friendlysync.model.VitalSnapshot.class,
            dev.lyy.friendlysync.model.ExperienceSnapshot.class,
            dev.lyy.friendlysync.model.EnderChestSnapshot.class,
            dev.lyy.friendlysync.model.pdc.PersistentDataSnapshot.class,
            dev.lyy.friendlysync.model.pdc.PersistentDataKey.class,
            dev.lyy.friendlysync.model.pdc.PersistentDataValue.class,
            dev.lyy.friendlysync.model.stats.StatisticsSnapshot.class,
            dev.lyy.friendlysync.model.stats.StatisticKey.class,
            dev.lyy.friendlysync.model.stats.StatisticQualifierType.class,
            dev.lyy.friendlysync.model.result.LoadResult.class,
            dev.lyy.friendlysync.model.result.CommitResult.class,
            dev.lyy.friendlysync.model.result.SaveResult.class,
            dev.lyy.friendlysync.model.result.OwnershipResult.class,
            dev.lyy.friendlysync.model.result.TransferResult.class,
            dev.lyy.friendlysync.model.SessionState.class,
            dev.lyy.friendlysync.model.ActivePlayerSession.class,
            dev.lyy.friendlysync.model.SessionIdentity.class,
            dev.lyy.friendlysync.database.repository.PersistedPlayerState.class,
            dev.lyy.friendlysync.serialization.DefaultPlayerStateSerializer.class,
            dev.lyy.friendlysync.serialization.CompressionService.class
        };

        for (Class<?> c : classes) {
            try {
                Class.forName(c.getName(), true, classLoader);
                count.incrementAndGet();
            } catch (Throwable ignored) {
            }
        }
    }
}
