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
        ConnectionResult result = connection.execCommand("echo hello", BecomeContext.empty());
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout().trim());
        assertTrue(result.stderr().isEmpty());
    }

    @Test
    void testExecCommandFailure() {
        ConnectionResult result = connection.execCommand("exit 42", BecomeContext.empty());
        assertEquals(42, result.exitCode());
    }

    @Test
    void testExecCommandStderr() {
        ConnectionResult result = connection.execCommand("echo error 1>&2", BecomeContext.empty());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().trim().isEmpty());
        assertEquals("error", result.stderr().trim());
    }

    @Test
    void testLargeOutput(@TempDir Path tempDir) throws IOException {
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

        ConnectionResult result = connection.execCommand(command, BecomeContext.empty());
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
