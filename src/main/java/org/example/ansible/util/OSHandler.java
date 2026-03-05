package org.example.ansible.util;

import java.util.List;

/**
 * Interface for OS-specific operations and information.
 */
public interface OSHandler {
    /**
     * Returns the default temporary directory for the OS.
     * @return The temp directory path.
     */
    default String getTempDir() {
        return System.getProperty("java.io.tmpdir");
    }

    /**
     * Returns the path separator for the OS.
     * @return The separator character.
     */
    String getSeparator();

    /**
     * Joins path parts using the OS-specific separator.
     * @param parts The parts to join.
     * @return The joined path string.
     */
    default String getJoinPath(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        return String.join(getSeparator(), parts);
    }

    /**
     * Returns the OS family name (e.g., "Linux", "Windows").
     * @return The OS family.
     */
    String getOSFamily();

    /**
     * Returns the shell executable and its argument to execute a command string.
     * @return A list containing the shell executable and the flag (e.g., ["/bin/sh", "-c"]).
     */
    List<String> getShellExecutable();

    /**
     * Checks if the OS supports 'sudo' for privilege escalation.
     * @return true if sudo is supported, false otherwise.
     */
    default boolean supportsSudo() {
        return true;
    }
}
