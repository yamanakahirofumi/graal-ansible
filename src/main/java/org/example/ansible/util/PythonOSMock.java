package org.example.ansible.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Java implementation of os module functions for Python bridge.
 * Absorb OS differences by utilizing OSHandler.
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
        // This is a common issue when GraalPy interacts with Windows paths
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

        // Use OSHandler's separator to normalize slashes
        String targetSeparator = osHandler.getSeparator();
        if ("\\".equals(targetSeparator)) {
            s = s.replace('/', '\\');
        } else {
            s = s.replace('\\', '/');
        }

        return s;
    }

    public boolean exists(String path) {
        if (path == null) return false;
        return Files.exists(Paths.get(normalizePath(path)));
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
        Path p = Paths.get(normalizePath(path));
        if (!Files.exists(p)) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            result.put("st_size", attrs.size());
            result.put("st_mtime", attrs.lastModifiedTime().toMillis() / 1000.0);
            result.put("st_atime", attrs.lastAccessTime().toMillis() / 1000.0);
            result.put("st_ctime", attrs.creationTime().toMillis() / 1000.0);

            int mode = attrs.isDirectory() ? 040000 : 0100000;

            if (attrs instanceof PosixFileAttributes) {
                PosixFileAttributes posix = (PosixFileAttributes) attrs;
                result.put("st_uid", 1000); // Default for mock
                result.put("st_gid", 1000);
                mode |= decodePermissions(posix.permissions());
            } else {
                result.put("st_uid", 0);
                result.put("st_gid", 0);
                // Default permissions if not POSIX
                mode |= attrs.isDirectory() ? 0755 : 0644;
            }
            result.put("st_mode", mode);

        } catch (IOException e) {
            return null;
        }
        return result;
    }

    private int decodePermissions(Set<PosixFilePermission> permissions) {
        int mode = 0;
        if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
        if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
        if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
        if (permissions.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
        if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
        if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
        if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
        return mode;
    }

    // POSIX-only functions mocks - can be further refined using OSHandler
    public int geteuid() {
        return "Linux".equals(osHandler.getOSFamily()) || "Darwin".equals(osHandler.getOSFamily()) ? 0 : 0;
    }

    public int getuid() {
        return "Linux".equals(osHandler.getOSFamily()) || "Darwin".equals(osHandler.getOSFamily()) ? 0 : 0;
    }

    public void chown(String path, int uid, int gid) {
        // Logic could be added here to use java.nio.file.attribute.UserPrincipalLookupService if supported
    }

    public void lchown(String path, int uid, int gid) { }
    public void lchmod(String path, int mode) { }
    public void setegid(int gid) { }
    public void seteuid(int uid) { }
    public void setgid(int gid) { }
    public void setuid(int uid) { }
}
