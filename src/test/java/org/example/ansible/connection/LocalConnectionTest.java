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
        assertEquals("hello", result.stdout().trim());
        assertTrue(result.stderr().isEmpty());
    }

    @Test
    void testExecCommandFailure() {
        // Exit with non-zero code
        // Use a portable way to exit with a code.
        // /bin/sh -c "exit 42" works on Unix.
        // cmd.exe /c "exit 42" works on Windows.
        ConnectionResult result = connection.execCommand("exit 42", false);
        assertEquals(42, result.exitCode());
    }

    @Test
    void testExecCommandStderr() {
        // Use a portable way to write to stderr if possible, or just accept that echo >&2 might be slightly different
        // On Windows cmd.exe: echo error 1>&2
        // On Unix sh: echo error >&2
        // Actually "echo error 1>&2" works on both.
        ConnectionResult result = connection.execCommand("echo error 1>&2", false);
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().trim().isEmpty());
        assertEquals("error", result.stderr().trim());
    }

    @Test
    void testLargeOutput(@TempDir Path tempDir) throws IOException {
        // Generate large output to test potential deadlocks (standard buffer is ~64KB)
        // Creating a large file and reading it back is more portable than seq | tr
        Path largeFile = tempDir.resolve("large.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append(i).append(" ");
        }
        String content = sb.toString();
        Files.writeString(largeFile, content);

        String command;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command = "type " + largeFile.toAbsolutePath();
        } else {
            command = "cat " + largeFile.toAbsolutePath();
        }

        ConnectionResult result = connection.execCommand(command, false);
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().length() > 65536, "Output should be larger than 64KB, but was " + result.stdout().length());
        assertEquals(content.trim(), result.stdout().trim());
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
