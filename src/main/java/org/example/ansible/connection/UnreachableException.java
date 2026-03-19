package org.example.ansible.connection;

/**
 * Exception thrown when a host is unreachable.
 */
public class UnreachableException extends RuntimeException {
    public UnreachableException(String message) {
        super(message);
    }
    public UnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
