package org.example.ansible.util;

/**
 * Interface for OS-specific operations and information.
 */
public interface OSHandler {
    /**
     * Returns the default temporary directory for the OS.
     * @return The temp directory path.
     */
    String getTempDir();

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
}
