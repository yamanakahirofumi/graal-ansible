package org.example.ansible.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PythonOSMockTest {

    private PythonOSMock osMock;

    @BeforeEach
    void setUp() {
        osMock = new PythonOSMock();
    }

    @Test
    void testNormalizePath() {
        // Test standard string
        assertEquals("test/path", osMock.normalizePath("test/path").replace('\\', '/'));

        // Test Python byte string representation
        assertEquals("test/path", osMock.normalizePath("b'test/path'").replace('\\', '/'));

        // Test Python byte string with hex escapes
        // b'test\x2fpath' -> test/path (\x2f is '/')
        assertEquals("test/path", osMock.normalizePath("b'test\\x2fpath'").replace('\\', '/'));

        // Test Windows drive letter prefix (on non-Windows it should just strip leading /)
        String winPath = "/C:/test";
        String normalizedWinPath = osMock.normalizePath(winPath);
        assertTrue(normalizedWinPath.equals("C:/test") || normalizedWinPath.equals("C:\\test"),
            "Expected C:/test or C:\\test, but got " + normalizedWinPath);

        // Test null
        assertNull(osMock.normalizePath(null));
    }

    @Test
    void testFileSystemOperations(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("testfile.txt");
        Files.write(testFile, "hello".getBytes());

        Path testDir = tempDir.resolve("testdir");
        Files.createDirectory(testDir);

        // Test exists
        assertTrue(osMock.exists(testFile.toString()));
        assertTrue(osMock.exists(testDir.toString()));
        assertFalse(osMock.exists(tempDir.resolve("nonexistent").toString()));

        // Test mkdir
        String newDir = tempDir.resolve("newdir").toString();
        osMock.mkdir(newDir);
        assertTrue(Files.exists(tempDir.resolve("newdir")));
        assertTrue(Files.isDirectory(tempDir.resolve("newdir")));

        // Test makedirs
        String nestedDir = tempDir.resolve("a/b/c").toString();
        osMock.makedirs(nestedDir);
        assertTrue(Files.exists(tempDir.resolve("a/b/c")));
        assertTrue(Files.isDirectory(tempDir.resolve("a/b/c")));

        // Test makedirs exist_ok
        osMock.makedirs(nestedDir, 0777, true); // Should not throw

        // Test makedirs exist_ok = false (default)
        osMock.makedirs(nestedDir, 0777, false);
    }

    @Test
    void testStat(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("statfile.txt");
        byte[] content = "some content".getBytes();
        Files.write(testFile, content);

        PythonOSMock.StatResult result = osMock.stat(testFile.toString());
        assertNotNull(result);

        // List-style access
        // st_mode, st_ino, st_dev, st_nlink, st_uid, st_gid, st_size, st_atime, st_mtime, st_ctime
        assertTrue(result.get(0).longValue() > 0); // st_mode
        assertEquals(0L, result.get(1).longValue()); // st_ino
        assertEquals(0L, result.get(2).longValue()); // st_dev
        assertEquals(1L, result.get(3).longValue()); // st_nlink
        assertEquals(0L, result.get(4).longValue()); // st_uid
        assertEquals(0L, result.get(5).longValue()); // st_gid
        assertEquals((long)content.length, result.get(6).longValue()); // st_size

        // Attribute-style access
        assertTrue(result.st_mode() > 0);
        assertEquals(content.length, result.st_size());
        assertTrue(result.st_atime() > 0);
        assertTrue(result.st_mtime() > 0);
        assertTrue(result.st_ctime() > 0);

        // Test stat on directory
        PythonOSMock.StatResult dirResult = osMock.stat(tempDir.toString());
        assertNotNull(dirResult);
        assertTrue(dirResult.st_mode() > 0);
        // check directory bit (040000)
        assertTrue((dirResult.st_mode() & 040000) != 0);
    }

    @Test
    void testIdentityAndNoOp() {
        assertEquals(0, osMock.getuid());
        assertEquals(0, osMock.geteuid());
        assertEquals(0, osMock.getgid());
        assertEquals(0, osMock.getegid());

        // Should not throw exceptions
        assertDoesNotThrow(() -> {
            osMock.chown("path", 0, 0);
            osMock.lchown("path", 0, 0);
            osMock.lchmod("path", 0644);
            osMock.setuid(0);
            osMock.setgid(0);
            osMock.seteuid(0);
            osMock.setegid(0);
        });
    }
}
