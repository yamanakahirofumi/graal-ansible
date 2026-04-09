package org.example.ansible.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Java implementation of os module functions for Python bridge.
 */
public class PythonOSMock {
    private final OSHandler osHandler;

    public PythonOSMock() {
        this(OSHandlerFactory.getHandler());
    }

    public PythonOSMock(OSHandler osHandler) {
        this.osHandler = osHandler;
    }

    /**
     * Normalizes a path string, handling Windows/Linux differences.
     */
    public String normalizePath(String path) {
        if (path == null) return null;
        String s = path;

        // Remove leading slashes from Windows absolute paths (e.g. /C:\... -> C:\...)
        while (true) {
            if (s.length() > 3 && (s.charAt(0) == '/' || s.charAt(0) == '\\') && s.charAt(2) == ':' && Character.isLetter(s.charAt(1))) {
                s = s.substring(1);
            } else if (s.length() > 2 && (s.charAt(0) == '/' || s.charAt(0) == '\\') && (s.charAt(1) == '/' || s.charAt(1) == '\\') && s.charAt(2) != '\\') {
                if (s.length() > 3 && s.charAt(3) == ':') {
                    s = s.substring(1);
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        // Fix backslashes for Windows if we are on Windows
        if (System.getProperty("os.name").toLowerCase().contains("win") && s.contains(":")) {
            s = s.replace('/', '\\');
        }

        return s;
    }

    public boolean exists(String path) {
        if (path == null) return false;
        return new File(normalizePath(path)).exists();
    }

    public void makedirs(String path, int mode, boolean existOk) throws IOException {
        Path p = Paths.get(normalizePath(path));
        if (existOk && Files.exists(p)) {
            return;
        }
        Files.createDirectories(p);
    }

    public void mkdir(String path, int mode) throws IOException {
        Files.createDirectory(Paths.get(normalizePath(path)));
    }

    public Map<String, Object> stat(String path) {
        File file = new File(normalizePath(path));
        if (!file.exists()) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        // Basic stat info
        result.put("st_mode", file.isDirectory() ? 040755 : 0100644);
        result.put("st_size", file.length());
        result.put("st_mtime", file.lastModified() / 1000.0);
        result.put("st_atime", file.lastModified() / 1000.0);
        result.put("st_ctime", file.lastModified() / 1000.0);
        result.put("st_uid", 0);
        result.put("st_gid", 0);
        return result;
    }

    // POSIX-only functions mocks
    public int geteuid() { return 0; }
    public int getuid() { return 0; }
    public void chown(String path, int uid, int gid) { /* no-op */ }
    public void lchown(String path, int uid, int gid) { /* no-op */ }
    public void lchmod(String path, int mode) { /* no-op */ }
    public void setegid(int gid) { /* no-op */ }
    public void seteuid(int uid) { /* no-op */ }
    public void setgid(int gid) { /* no-op */ }
    public void setuid(int uid) { /* no-op */ }
}
