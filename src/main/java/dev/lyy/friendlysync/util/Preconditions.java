package dev.lyy.friendlysync.util;

import java.util.Objects;

/**
 * Utility methods for defensive argument and state checking.
 */
public final class Preconditions {

    private Preconditions() {
    }

    public static <T> T checkNotNull(T reference, String errorMessage) {
        return Objects.requireNonNull(reference, errorMessage);
    }

    public static void checkArgument(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public static void checkState(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalStateException(errorMessage);
        }
    }

    public static String checkNotEmpty(String string, String errorMessage) {
        if (string == null || string.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return string;
    }
}
