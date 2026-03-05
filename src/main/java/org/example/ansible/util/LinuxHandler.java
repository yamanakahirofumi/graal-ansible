package org.example.ansible.util;

import java.util.List;

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

    @Override
    public List<String> getShellExecutable() {
        return List.of("/bin/sh", "-c");
    }
}
