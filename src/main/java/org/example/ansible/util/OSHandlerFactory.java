package org.example.ansible.util;

/**
 * Factory class to get the appropriate OSHandler.
 */
public class OSHandlerFactory {
    /**
     * Returns the handler for the current OS.
     * @return The OSHandler implementation.
     */
    public static OSHandler getHandler() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            return new LinuxHandler();
        } else if (osName.contains("win")) {
            return new WindowsHandler();
        } else if (osName.contains("mac")) {
            return new MacOSHandler();
        }
        return new LinuxHandler(); // Generic POSIX fallback
    }
}
