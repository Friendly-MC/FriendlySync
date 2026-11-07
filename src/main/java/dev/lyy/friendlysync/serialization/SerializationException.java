package dev.lyy.friendlysync.serialization;

/**
 * Exception thrown during binary snapshot serialization or deserialization failures.
 */
public class SerializationException extends RuntimeException {

    public SerializationException(String message) {
        super(message);
    }

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
