package dev.lyy.friendlysync.location;

/**
 * Validates, normalizes, and sanitizes cross-server location coordinates.
 * Strictly rejects non-finite values (NaN, Infinity) and out-of-bounds anomalies.
 */
public final class LocationValidator {

    private static final double MAX_WORLD_COORD = 30_000_000.0;
    private static final double MIN_Y_COORD = -2048.0;
    private static final double MAX_Y_COORD = 2048.0;

    private LocationValidator() {}

    /**
     * Checks if coordinates and rotation angles are mathematically finite and within valid boundaries.
     */
    public static boolean isValid(double x, double y, double z, float yaw, float pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return false;
        }
        if (Math.abs(x) > MAX_WORLD_COORD || Math.abs(z) > MAX_WORLD_COORD) {
            return false;
        }
        return y >= MIN_Y_COORD && y <= MAX_Y_COORD;
    }

    /**
     * Checks if a snapshot possesses valid coordinates.
     */
    public static boolean isValid(dev.lyy.friendlysync.model.LocationSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return isValid(snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
    }

    /**
     * Normalizes yaw to the standard range of [-180.0, 180.0].
     */
    public static float normalizeYaw(float yaw) {
        if (!Float.isFinite(yaw)) {
            return 0.0f;
        }
        yaw %= 360.0f;
        if (yaw >= 180.0f) {
            yaw -= 360.0f;
        } else if (yaw < -180.0f) {
            yaw += 360.0f;
        }
        return yaw;
    }

    /**
     * Clamps pitch to the legal Minecraft camera angle range of [-90.0, 90.0].
     */
    public static float clampPitch(float pitch) {
        if (!Float.isFinite(pitch)) {
            return 0.0f;
        }
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }
}
