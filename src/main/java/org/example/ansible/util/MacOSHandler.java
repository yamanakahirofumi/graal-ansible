package org.example.ansible.util;

/**
 * macOS implementation of OSHandler.
 */
public class MacOSHandler extends LinuxHandler {
    @Override
    public String getOSFamily() {
        return "Darwin";
    }
}
