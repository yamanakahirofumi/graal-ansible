package org.example.ansible.util;

/**
 * OSHandler implementation for macOS.
 */
public class MacOSHandler extends LinuxHandler {
    @Override
    public String getOSFamily() {
        return "Darwin";
    }
}
