package dev.lyy.friendlysync.config;

/**
 * Exception thrown when a configuration setting is missing or invalid.
 */
public class ConfigValidationException extends RuntimeException {

    public ConfigValidationException(String message) {
        super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
