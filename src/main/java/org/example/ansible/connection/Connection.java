package org.example.ansible.connection;

import java.nio.file.Path;

/**
 * Interface for connection plugins.
 */
public interface Connection extends AutoCloseable {
    /**
     * Gets the transport type string (e.g. "local", "ssh", "winrm", "docker").
     *
     * @return The transport type.
     */
    default String getTransport() {
        return "local";
    }

    /**
     * Connects to the target host.
     */
    void connect();

    /**
     * Executes a command on the target host.
     *
     * @param command       The command to execute.
     * @param becomeContext The privilege escalation context.
     * @param environment   The environment variables to set for the command.
     * @return The result of the command execution.
     */
    ConnectionResult execCommand(String command, BecomeContext becomeContext, java.util.Map<String, String> environment);

    /**
     * Transfers a file to the target host.
     *
     * @param localPath  The path on the control node.
     * @param remotePath The path on the target host as a string to avoid OS-specific separators.
     */
    void putFile(Path localPath, String remotePath);

    /**
     * Fetches a file from the target host.
     *
     * @param remotePath The path on the target host as a string to avoid OS-specific separators.
     * @param localPath  The path on the control node.
     */
    void fetchFile(String remotePath, Path localPath);

    /**
     * Closes the connection.
     */
    @Override
    void close();
}
