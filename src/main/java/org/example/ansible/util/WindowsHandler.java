package org.example.ansible.util;

/**
 * Windows implementation of OSHandler.
 */
public class WindowsHandler implements OSHandler {
    @Override
    public String getTempDir() {
        return "C:\\Temp";
    }

    @Override
    public String getSeparator() {
        return "\\";
    }

    @Override
    public String getOSFamily() {
        return "Windows";
    }
}
