package org.example.ansible.util;

/**
 * Linux implementation of OSHandler.
 */
public class LinuxHandler implements OSHandler {
    @Override
    public String getTempDir() {
        return "/tmp";
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public String getOSFamily() {
        return "Linux";
    }
}
