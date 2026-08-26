package org.example.ansible.connection;

import org.example.ansible.inventory.Host;
import org.example.ansible.util.OSHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalConnectionTest {

    private final LocalConnection connection = new LocalConnection();

    private static class TestableLocalConnection extends LocalConnection {
        private final Process mockProcess;
        private final List<ProcessBuilder> capturedProcessBuilders = new ArrayList<>();

        public TestableLocalConnection(OSHandler osHandler, Process mockProcess) {
            super(osHandler);
            this.mockProcess = mockProcess;
        }

        @Override
        Process startProcess(ProcessBuilder pb) throws IOException {
            capturedProcessBuilders.add(pb);
            if (mockProcess == null) {
                throw new IOException("Simulated process start failure");
            }
            return mockProcess;
        }

        public List<ProcessBuilder> getCapturedProcessBuilders() {
            return capturedProcessBuilders;
        }
    }

    private Process createMockProcess(int exitCode, String stdout, String stderr, ByteArrayOutputStream capturedStdin) throws Exception {
        Process mockProcess = mock(Process.class);
        when(mockProcess.waitFor()).thenReturn(exitCode);

        InputStream stdoutStream = new ByteArrayInputStream(stdout.getBytes());
        InputStream stderrStream = new ByteArrayInputStream(stderr.getBytes());

        when(mockProcess.getInputStream()).thenReturn(stdoutStream);
        when(mockProcess.getErrorStream()).thenReturn(stderrStream);
        when(mockProcess.getOutputStream()).thenReturn(capturedStdin != null ? capturedStdin : new ByteArrayOutputStream());

        return mockProcess;
    }

    @Test
    void testExecCommandSuccess() {
        ConnectionResult result = connection.execCommand("echo hello", BecomeContext.empty(), null);
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout().trim());
        assertTrue(result.stderr().isEmpty());
    }

    @Test
    void testExecCommandFailure() {
        ConnectionResult result = connection.execCommand("exit 42", BecomeContext.empty(), null);
        assertEquals(42, result.exitCode());
    }

    @Test
    void testExecCommandStderr() {
        ConnectionResult result = connection.execCommand("echo error 1>&2", BecomeContext.empty(), null);
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

        ConnectionResult result = connection.execCommand(command, BecomeContext.empty(), null);
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
        connection.putFile(src, dest.toString());
        assertTrue(Files.exists(dest));
        assertEquals(content, Files.readString(dest));

        // fetchFile
        Path fetched = tempDir.resolve("fetched.txt");
        connection.fetchFile(dest.toString(), fetched);
        assertTrue(Files.exists(fetched));
        assertEquals(content, Files.readString(fetched));
    }

    @Test
    void testExecCommandWithSudoBecome() throws Exception {
        OSHandler mockOSHandler = mock(OSHandler.class);
        when(mockOSHandler.supportsSudo()).thenReturn(true);
        when(mockOSHandler.getShellExecutable()).thenReturn(List.of("/bin/sh", "-c"));

        ByteArrayOutputStream capturedStdin = new ByteArrayOutputStream();
        Process mockProcess = createMockProcess(0, "root_user\n", "", capturedStdin);

        TestableLocalConnection conn = new TestableLocalConnection(mockOSHandler, mockProcess);
        BecomeContext become = new BecomeContext(true, "sudo", "admin", "-n -H", "secret123");

        ConnectionResult result = conn.execCommand("whoami", become, null);

        assertEquals(0, result.exitCode());
        assertEquals("root_user", result.stdout().trim());

        List<ProcessBuilder> captured = conn.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> commandList = captured.get(0).command();

        assertTrue(commandList.contains("sudo"));
        assertTrue(commandList.contains("-S"));
        assertTrue(commandList.contains("-p"));
        assertTrue(commandList.contains("BECOME-PROMPT"));
        assertTrue(commandList.contains("-u"));
        assertTrue(commandList.contains("admin"));
        assertTrue(commandList.contains("-n"));
        assertTrue(commandList.contains("-H"));
        assertTrue(commandList.contains("whoami"));

        assertEquals("secret123\n", capturedStdin.toString());
    }

    @Test
    void testExecCommandWithSuBecome() throws Exception {
        OSHandler mockOSHandler = mock(OSHandler.class);
        when(mockOSHandler.supportsSudo()).thenReturn(true);
        when(mockOSHandler.getShellExecutable()).thenReturn(List.of("/bin/sh", "-c"));

        Process mockProcess = createMockProcess(0, "su_user\n", "", null);

        TestableLocalConnection conn = new TestableLocalConnection(mockOSHandler, mockProcess);
        BecomeContext become = new BecomeContext(true, "su", "root", null, null);

        ConnectionResult result = conn.execCommand("id", become, null);

        assertEquals(0, result.exitCode());
        assertEquals("su_user", result.stdout().trim());

        List<ProcessBuilder> captured = conn.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> commandList = captured.get(0).command();

        assertTrue(commandList.contains("su"));
        assertTrue(commandList.contains("root"));
        assertTrue(commandList.contains("-c"));
        assertTrue(commandList.contains("id"));
    }

    @Test
    void testExecCommandWithEnvironment() throws Exception {
        OSHandler mockOSHandler = mock(OSHandler.class);
        when(mockOSHandler.supportsSudo()).thenReturn(false);
        when(mockOSHandler.getShellExecutable()).thenReturn(List.of("/bin/sh", "-c"));

        Process mockProcess = createMockProcess(0, "env_val\n", "", null);

        TestableLocalConnection conn = new TestableLocalConnection(mockOSHandler, mockProcess);
        Map<String, String> env = Map.of("MY_VAR", "MY_VAL");

        ConnectionResult result = conn.execCommand("echo $MY_VAR", BecomeContext.empty(), env);

        assertEquals(0, result.exitCode());
        List<ProcessBuilder> captured = conn.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        assertEquals("MY_VAL", captured.get(0).environment().get("MY_VAR"));
    }

    @Test
    void testExecCommandIOException() {
        OSHandler mockOSHandler = mock(OSHandler.class);
        when(mockOSHandler.supportsSudo()).thenReturn(false);
        when(mockOSHandler.getShellExecutable()).thenReturn(List.of("/bin/sh", "-c"));

        TestableLocalConnection conn = new TestableLocalConnection(mockOSHandler, null);
        ConnectionResult result = conn.execCommand("some_cmd", BecomeContext.empty(), null);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Simulated process start failure"));
    }

    @Test
    void testExecCommandInterruptedException() throws Exception {
        OSHandler mockOSHandler = mock(OSHandler.class);
        when(mockOSHandler.supportsSudo()).thenReturn(false);
        when(mockOSHandler.getShellExecutable()).thenReturn(List.of("/bin/sh", "-c"));

        Process mockProcess = mock(Process.class);
        when(mockProcess.waitFor()).thenThrow(new InterruptedException("Interrupted test"));
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(mockProcess.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

        TestableLocalConnection conn = new TestableLocalConnection(mockOSHandler, mockProcess);
        ConnectionResult result = conn.execCommand("sleep 100", BecomeContext.empty(), null);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Interrupted: Interrupted test"));
    }

    @Test
    void testDefaultConnectionFactoryIntegration() {
        DefaultConnectionFactory factory = new DefaultConnectionFactory();
        Host host = new Host("localhost", null);

        Map<String, Object> variables = new HashMap<>();
        variables.put("ansible_connection", "local");

        Connection conn = factory.createConnection(host, variables);
        assertNotNull(conn);
        assertTrue(conn instanceof LocalConnection);
    }
}
