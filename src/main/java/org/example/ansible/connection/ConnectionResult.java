package org.example.ansible.connection;

/**
 * Represents the result of a command execution via a connection plugin.
 *
 * @param stdout   The standard output of the command.
 * @param stderr   The standard error of the command.
 * @param exitCode The exit code of the command.
 */
public record ConnectionResult(String stdout, String stderr, int exitCode) {
}
