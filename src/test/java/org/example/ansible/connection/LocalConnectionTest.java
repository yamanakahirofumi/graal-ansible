package org.example.ansible.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalConnectionTest {

    private final LocalConnection connection = new LocalConnection();

    @Test
    void testExecCommandSuccess() {
        ConnectionResult result = connection.execCommand("echo hello", false);
        assertEquals(0, result.exitCode());
        assertEquals("hello\n", result.stdout());
        assertTrue(result.stderr().isEmpty());
    }

    @Test
    void testExecCommandFailure() {
        // Exit with non-zero code
        ConnectionResult result = connection.execCommand("exit 42", false);
        assertEquals(42, result.exitCode());
    }

    @Test
    void testExecCommandStderr() {
        ConnectionResult result = connection.execCommand("echo error >&2", false);
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().isEmpty());
        assertEquals("error\n", result.stderr());
    }

    @Test
    void testLargeOutput() {
        // Generate large output to test potential deadlocks (standard buffer is ~64KB)
        // Using seq and tr to generate a predictable large stream that works in /bin/sh
        String command = "seq 1 20000 | tr -d '\\n'";
        ConnectionResult result = connection.execCommand(command, false);
        assertEquals(0, result.exitCode());
        // seq 1 20000 produces numbers. 1-9 (9 chars), 10-99 (90*2=180), 100-999 (900*3=2700), 1000-9999 (9000*4=36000), 10000-20000 (10001*5=50005)
        // Total: 9 + 180 + 2700 + 36000 + 50005 = 88894
        assertTrue(result.stdout().length() > 65536, "Output should be larger than 64KB, but was " + result.stdout().length());
    }

    @Test
    void testFileOperations(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("src.txt");
        Path dest = tempDir.resolve("dest.txt");
        String content = "test content";
        Files.writeString(src, content);

        // putFile
        connection.putFile(src, dest);
        assertTrue(Files.exists(dest));
        assertEquals(content, Files.readString(dest));

        // fetchFile
        Path fetched = tempDir.resolve("fetched.txt");
        connection.fetchFile(dest, fetched);
        assertTrue(Files.exists(fetched));
        assertEquals(content, Files.readString(fetched));
    }
}
