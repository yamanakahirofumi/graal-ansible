package org.example.ansible.util;

import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Java implementation of os module functions for Python bridge.
 * Designed for direct assignment in Python (e.g., os.stat = os_java.stat).
 */
public class PythonOSMock {
    private final OSHandler osHandler;
    private Value statResultFactory;
    private Value exceptionHandler;

    public PythonOSMock() {
        this(OSHandlerFactory.getHandler());
    }

    public PythonOSMock(OSHandler osHandler) {
        this.osHandler = osHandler;
    }

    public OSHandler getOSHandler() {
        return osHandler;
    }

    public void setPythonClasses(Value statResultFactory, Value exceptionHandler) {
        this.statResultFactory = statResultFactory;
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Sequence and attribute compatible stat result for Python.
     */
    public static class StatResult extends ArrayList<Number> {
        public long st_mode;
        public long st_ino = 0;
        public long st_dev = 0;
        public long st_nlink = 1;
        public long st_uid = 0;
        public long st_gid = 0;
        public long st_size;
        public double st_atime;
        public double st_mtime;
        public double st_ctime;

        public StatResult(long mode, long size, double atime, double mtime, double ctime) {
            this.st_mode = mode;
            this.st_size = size;
            this.st_atime = atime;
            this.st_mtime = mtime;
            this.st_ctime = ctime;
            // Order must match Python's os.stat_result tuple
            addAll(List.of(st_mode, st_ino, st_dev, st_nlink, st_uid, st_gid, st_size, st_atime, st_mtime, st_ctime));
        }

        // Record-style getters for attribute access via GraalPy
        public long st_mode() {
            return st_mode;
        }

        public long st_ino() {
            return st_ino;
        }

        public long st_dev() {
            return st_dev;
        }

        public long st_nlink() {
            return st_nlink;
        }

        public long st_uid() {
            return st_uid;
        }

        public long st_gid() {
            return st_gid;
        }

        public long st_size() {
            return st_size;
        }

        public double st_atime() {
            return st_atime;
        }

        public double st_mtime() {
            return st_mtime;
        }

        public double st_ctime() {
            return st_ctime;
        }
    }

    private String unescape(String s) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char next = s.charAt(i + 1);
                    if (next == 'x' && i + 3 < s.length()) {
                        out.write(Integer.parseInt(s.substring(i + 2, i + 4), 16));
                        i += 3;
                        continue;
                    } else if (next >= '0' && next <= '7') {
                        int j = i + 1;
                        while (j < s.length() && j < i + 4 && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                            j++;
                        }
                        out.write(Integer.parseInt(s.substring(i + 1, j), 8));
                        i = j - 1;
                        continue;
                    } else {
                        int escape = switch (next) {
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case '\\' -> '\\';
                            case '\'' -> '\'';
                            case '\"' -> '\"';
                            default -> -1;
                        };
                        if (escape != -1) {
                            out.write(escape);
                            i++;
                            continue;
                        }
                    }
                }
                out.write(c);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    public String normalizePath(String path) {
        if (path == null) return null;
        String s = path;
        if (s.startsWith("b'") && s.endsWith("'") && s.length() >= 3) {
            s = unescape(s.substring(2, s.length() - 1));
        }
        if (s.length() > 2 && (s.charAt(0) == '/' || s.charAt(0) == '\\') && s.charAt(2) == ':' && Character.isLetter(s.charAt(1))) {
            s = s.substring(1);
        }
        if ("Windows".equals(osHandler.getOSFamily())) {
            if (s.contains(":")) s = s.replace('/', '\\');
        } else {
            if (s.startsWith("/") || s.contains("/")) s = s.replace('\\', '/');
        }
        return s;
    }

    private Path toPath(String p) {
        String s = normalizePath(p);
        return s != null ? Paths.get(s) : null;
    }

    public boolean exists(String path) {
        try {
            Path p = toPath(path);
            return p != null && Files.exists(p);
        } catch (Exception e) {
            return false;
        }
    }

    public void makedirs(String name, int mode, boolean exist_ok) throws IOException {
        Path p = toPath(name);
        if (p == null) return;
        if (exist_ok && Files.exists(p)) return;
        Files.createDirectories(p);
    }

    public void makedirs(String name, int mode) throws IOException {
        makedirs(name, mode, false);
    }

    public void makedirs(String name) throws IOException {
        makedirs(name, 0777, false);
    }

    public void mkdir(String path, int mode) throws IOException {
        Path p = toPath(path);
        if (p != null) Files.createDirectory(p);
    }

    public void mkdir(String path) throws IOException {
        mkdir(path, 0777);
    }

    public List<String> listdir(String path) throws IOException {
        Path p = toPath(path);
        if (p == null || !Files.exists(p)) {
            if (exceptionHandler != null) {
                exceptionHandler.execute("[Errno 2] No such file or directory: '" + path + "'");
            }
            return null;
        }
        try (var stream = Files.list(p)) {
            return stream.map(Path::getFileName).map(Path::toString).collect(Collectors.toList());
        }
    }

    /**
     * Native Java implementation of stat. Returns StatResult.
     */
    public StatResult stat(String path) {
        Path p = toPath(path);
        if (p == null || (!Files.exists(p) && !Files.isSymbolicLink(p))) return null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            long mode = 0;
            if (attrs.isDirectory()) {
                mode = 040000;
            } else if (attrs.isRegularFile()) {
                mode = 0100000;
            } else if (attrs.isSymbolicLink()) {
                mode = 0120000;
            } else if (attrs.isOther()) {
                // Handle special files (sockets, pipes, devices) if possible.
                // In Java, BasicFileAttributes.isOther() is true for these.
                // We might need to use more specific checks or default to a generic "other" mode.
                // For simplicity, we can try to detect them via the first character of `ls -ld` if needed,
                // but let's try a common mask for non-regular/non-dir/non-link.
                mode = 0; // Will be refined by PosixFileAttributes if available
            }

            if (attrs instanceof PosixFileAttributes) {
                PosixFileAttributes posix = (PosixFileAttributes) attrs;
                mode |= decodePermissions(posix.permissions());

                // PosixFileAttributes doesn't directly give us S_IFBLK, S_IFCHR, etc.
                // But we can use the isDirectory, isRegularFile, isSymbolicLink methods which we already did.
                // If it's "other", we might still be stuck with 0 mode prefix unless we use specific NIO features.
            } else {
                mode |= attrs.isDirectory() ? 0755 : 0644;
            }

            return new StatResult(
                    mode, attrs.size(),
                    attrs.lastAccessTime().toMillis() / 1000.0,
                    attrs.lastModifiedTime().toMillis() / 1000.0,
                    attrs.creationTime().toMillis() / 1000.0
            );
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Python-compatible stat implementation. Uses factories to return os.stat_result.
     */
    public Object statPython(String path, Object... args) {
        StatResult res = stat(path);
        if (res == null) {
            if (exceptionHandler != null) {
                exceptionHandler.execute("[Errno 2] No such file or directory: '" + path + "'");
            }
            return null;
        }

        if (statResultFactory != null) {
            return statResultFactory.execute(List.copyOf(res));
        }
        return res;
    }

    private int decodePermissions(Set<PosixFilePermission> permissions) {
        int mode = 0;
        for (PosixFilePermission p : permissions) {
            mode |= switch (p) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 0040;
                case GROUP_WRITE -> 0020;
                case GROUP_EXECUTE -> 0010;
                case OTHERS_READ -> 0004;
                case OTHERS_WRITE -> 0002;
                case OTHERS_EXECUTE -> 0001;
            };
        }
        return mode;
    }

    public int geteuid() {
        return 0;
    }

    public int getuid() {
        return 0;
    }

    public int getegid() {
        return 0;
    }

    public int getgid() {
        return 0;
    }

    public void chown(String path, int uid, int gid) {
    }

    public void lchown(String path, int uid, int gid) {
    }

    public void lchmod(String path, int mode) {
    }

    public void setegid(int gid) {
    }

    public void seteuid(int uid) {
    }

    public void setgid(int gid) {
    }

    public void setuid(int uid) {
    }
}
