package org.example.ansible.util;

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
import org.graalvm.polyglot.Value;

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

    public void setPythonClasses(Value statResultFactory, Value exceptionHandler) {
        this.statResultFactory = statResultFactory;
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Sequence and attribute compatible stat result for Python.
     */
    public static class StatResult extends ArrayList<Object> {
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
            add(st_mode); add(st_ino); add(st_dev); add(st_nlink);
            add(st_uid); add(st_gid); add(st_size);
            add(st_atime); add(st_mtime); add(st_ctime);
        }

        // Record-style getters for attribute access via GraalPy
        public long st_mode() { return st_mode; }
        public long st_ino() { return st_ino; }
        public long st_dev() { return st_dev; }
        public long st_nlink() { return st_nlink; }
        public long st_uid() { return st_uid; }
        public long st_gid() { return st_gid; }
        public long st_size() { return st_size; }
        public double st_atime() { return st_atime; }
        public double st_mtime() { return st_mtime; }
        public double st_ctime() { return st_ctime; }
    }

    private String convertToString(Object o) {
        if (o == null) return null;
        String s;
        if (o instanceof String) {
            s = (String) o;
        } else if (o instanceof byte[]) {
            s = new String((byte[]) o, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            s = o.toString();
        }
        if (s.startsWith("b'") && s.endsWith("'") && s.length() >= 3) {
            s = s.substring(2, s.length() - 1);
            return unescape(s);
        }
        return s;
    }

    private String unescape(String s) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 3 < s.length() && s.charAt(i + 1) == 'x') {
                    out.write(Integer.parseInt(s.substring(i + 2, i + 4), 16));
                    i += 3;
                } else {
                    out.write(c);
                }
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return s; }
    }

    public String normalizePath(Object path) {
        String s = convertToString(path);
        if (s == null) return null;
        if (s.length() > 2 && (s.charAt(0) == '/' || s.charAt(0) == '\\') && s.charAt(2) == ':' && Character.isLetter(s.charAt(1))) {
            s = s.substring(1);
        }
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (s.contains(":")) s = s.replace('/', '\\');
        } else {
            if (s.startsWith("/") || s.contains("/")) s = s.replace('\\', '/');
        }
        return s;
    }

    private Path toPath(Object p) {
        String s = normalizePath(p);
        return s != null ? Paths.get(s) : null;
    }

    public boolean exists(Object path) {
        try {
            Path p = toPath(path);
            return p != null && Files.exists(p);
        } catch (Exception e) { return false; }
    }

    public void makedirs(Object name, int mode, boolean exist_ok) throws IOException {
        Path p = toPath(name);
        if (p == null) return;
        if (exist_ok && Files.exists(p)) return;
        Files.createDirectories(p);
    }
    public void makedirs(Object name, int mode) throws IOException { makedirs(name, mode, false); }
    public void makedirs(Object name) throws IOException { makedirs(name, 0777, false); }

    public void mkdir(Object path, int mode) throws IOException {
        Path p = toPath(path);
        if (p != null) Files.createDirectory(p);
    }
    public void mkdir(Object path) throws IOException { mkdir(path, 0777); }

    /**
     * Native Java implementation of stat. Returns StatResult.
     */
    public StatResult stat(Object path) {
        Path p = toPath(path);
        if (p == null || !Files.exists(p)) return null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            long mode = attrs.isDirectory() ? 040000 : 0100000;
            if (attrs instanceof PosixFileAttributes) {
                mode |= decodePermissions(((PosixFileAttributes) attrs).permissions());
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
    public Object statPython(Object path, Object... args) {
        StatResult res = stat(path);
        if (res == null) {
            if (exceptionHandler != null) {
                exceptionHandler.execute("[Errno 2] No such file or directory: '" + path + "'");
            }
            return null;
        }

        if (statResultFactory != null) {
            List<Object> tuple = new ArrayList<>();
            tuple.add(res.st_mode);
            tuple.add(res.st_ino);
            tuple.add(res.st_dev);
            tuple.add(res.st_nlink);
            tuple.add(res.st_uid);
            tuple.add(res.st_gid);
            tuple.add(res.st_size);
            tuple.add(res.st_atime);
            tuple.add(res.st_mtime);
            tuple.add(res.st_ctime);
            return statResultFactory.execute(tuple);
        }
        return res;
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

    public int geteuid() { return 0; }
    public int getuid() { return 0; }
    public int getegid() { return 0; }
    public int getgid() { return 0; }
    public void chown(Object path, int uid, int gid) { }
    public void lchown(Object path, int uid, int gid) { }
    public void lchmod(Object path, int mode) { }
    public void setegid(int gid) { }
    public void seteuid(int uid) { }
    public void setgid(int gid) { }
    public void setuid(int uid) { }
}
