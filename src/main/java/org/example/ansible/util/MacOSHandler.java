package org.example.ansible.util;

/**
 * macOS implementation of OSHandler.
 * macOS uses POSIX-like paths, so it extends LinuxHandler.
 */
public class MacOSHandler extends LinuxHandler {
    @Override
    public String getOSFamily() {
        return "Darwin";
    }
}
