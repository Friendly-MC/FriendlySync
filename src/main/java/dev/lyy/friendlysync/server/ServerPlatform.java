package dev.lyy.friendlysync.server;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identifies the runtime server implementation and threading model.
 * Differentiates Paper, Folia, and CanvasMC (high-performance fork of Folia by CraftCanvasMC).
 */
public enum ServerPlatform {
    PAPER("Paper", false),
    FOLIA("Folia", true),
    CANVAS("CanvasMC", true);

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerPlatform.class);

    private final String displayName;
    private final boolean regionized;

    ServerPlatform(String displayName, boolean regionized) {
        this.displayName = displayName;
        this.regionized = regionized;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isRegionized() {
        return regionized;
    }

    /**
     * Detects the active server platform at runtime.
     *
     * @return detected ServerPlatform
     */
    public static ServerPlatform detect() {
        // 1. Check for CanvasMC first (Canvas extends Folia)
        if (isCanvas()) {
            return CANVAS;
        }

        if (isFolia()) {
            return FOLIA;
        }

        // 3. Fallback to standard Paper / single-threaded Bukkit
        return PAPER;
    }

    private static boolean isCanvas() {
        try {
            var buildInfo = io.papermc.paper.ServerBuildInfo.buildInfo();
            String brandName = buildInfo.brandName().toLowerCase();
            String brandId = buildInfo.brandId().asString().toLowerCase();
            if (brandName.contains("canvas") || brandId.contains("canvas")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class.forName("io.canvasmc.canvas.simd.SIMDDetection");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class.forName("io.canvasmc.canvas.Canvas");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class.forName("io.canvasmc.canvas.configuration.CanvasConfig");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        try {
            String name = Bukkit.getName().toLowerCase();
            String version = Bukkit.getVersion().toLowerCase();
            if (name.contains("canvas") || version.contains("canvas")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isFolia() {
        try {
            var buildInfo = io.papermc.paper.ServerBuildInfo.buildInfo();
            if (buildInfo.isBrandCompatible(Key.key("papermc", "folia"))) {
                return true;
            }
            String brandName = buildInfo.brandName().toLowerCase();
            String brandId = buildInfo.brandId().asString().toLowerCase();
            if (brandName.contains("folia") || brandId.contains("folia")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
        }

        try {
            String name = Bukkit.getName().toLowerCase();
            String version = Bukkit.getVersion().toLowerCase();
            if (name.contains("folia") || version.contains("folia")) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}
