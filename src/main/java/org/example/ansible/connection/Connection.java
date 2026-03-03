package org.example.ansible.connection;

import java.nio.file.Path;

/**
 * Interface for connection plugins.
 */
public interface Connection extends AutoCloseable {
    /**
     * Connects to the target host.
     */
    void connect();

    /**
     * Executes a command on the target host.
     *
     * @param command The command to execute.
     * @param sudo    Whether to use sudo for execution.
     * @return The result of the command execution.
     */
    ConnectionResult execCommand(String command, boolean sudo);

    /**
     * Transfers a file to the target host.
     *
     * @param localPath  The path on the control node.
     * @param remotePath The path on the target host.
     */
    void putFile(Path localPath, Path remotePath);

    /**
     * Fetches a file from the target host.
     *
     * @param remotePath The path on the target host.
     * @param localPath  The path on the control node.
     */
    void fetchFile(Path remotePath, Path localPath);

    /**
     * Closes the connection.
     */
    @Override
    void close();
}
