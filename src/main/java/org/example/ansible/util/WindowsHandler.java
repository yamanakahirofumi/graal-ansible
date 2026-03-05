package org.example.ansible.util;

import java.util.List;

/**
 * Windows implementation of OSHandler.
 */
public class WindowsHandler implements OSHandler {
    @Override
    public String getSeparator() {
        return "\\";
    }

    @Override
    public String getOSFamily() {
        return "Windows";
    }

    @Override
    public List<String> getShellExecutable() {
        return List.of("cmd.exe", "/c");
    }

    @Override
    public boolean supportsSudo() {
        return false;
    }
}
