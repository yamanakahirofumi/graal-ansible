package org.example.ansible.connection;

import org.example.ansible.inventory.Host;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerConnectionTest {

    // Test subclass of DockerConnection to allow injecting a mock Process
    private static class TestableDockerConnection extends DockerConnection {
        private final Process mockProcess;
        private final List<ProcessBuilder> capturedProcessBuilders = new ArrayList<>();

        public TestableDockerConnection(String containerName, String dockerUser, Process mockProcess) {
            super(containerName, dockerUser);
            this.mockProcess = mockProcess;
        }

        @Override
        Process startProcess(ProcessBuilder pb) throws IOException {
            capturedProcessBuilders.add(pb);
            return mockProcess;
        }

        public List<ProcessBuilder> getCapturedProcessBuilders() {
            return capturedProcessBuilders;
        }
    }

    private Process createMockProcess(int exitCode, String stdout, String stderr) throws Exception {
        Process mockProcess = mock(Process.class);
        when(mockProcess.waitFor()).thenReturn(exitCode);

        InputStream stdoutStream = new ByteArrayInputStream(stdout.getBytes());
        InputStream stderrStream = new ByteArrayInputStream(stderr.getBytes());
        OutputStream dummyOutStream = new ByteArrayOutputStream();

        when(mockProcess.getInputStream()).thenReturn(stdoutStream);
        when(mockProcess.getErrorStream()).thenReturn(stderrStream);
        when(mockProcess.getOutputStream()).thenReturn(dummyOutStream);

        return mockProcess;
    }

    @Test
    void testConnectSuccess() throws Exception {
        Process mockProcess = createMockProcess(0, "true\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        assertDoesNotThrow(connection::connect);

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();
        assertEquals(List.of("docker", "inspect", "-f", "{{.State.Running}}", "my-container"), command);
    }

    @Test
    void testConnectContainerNotFound() throws Exception {
        Process mockProcess = createMockProcess(1, "", "Error: No such object");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        UnreachableException ex = assertThrows(UnreachableException.class, connection::connect);
        assertTrue(ex.getMessage().contains("is not found"));
    }

    @Test
    void testConnectContainerNotRunning() throws Exception {
        Process mockProcess = createMockProcess(0, "false\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        UnreachableException ex = assertThrows(UnreachableException.class, connection::connect);
        assertTrue(ex.getMessage().contains("is not running"));
    }

    @Test
    void testExecCommandStandard() throws Exception {
        Process mockProcess = createMockProcess(0, "hello container\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        ConnectionResult result = connection.execCommand("echo hello", BecomeContext.empty(), null);

        assertEquals(0, result.exitCode());
        assertEquals("hello container", result.stdout().trim());
        assertTrue(result.stderr().isEmpty());

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();
        assertEquals(List.of("docker", "exec", "-u", "my-user", "my-container", "/bin/sh", "-c", "echo hello"), command);
    }

    @Test
    void testExecCommandWithEnvironment() throws Exception {
        Process mockProcess = createMockProcess(0, "hello container\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        Map<String, String> env = Map.of("VAR1", "VAL1", "VAR2", "VAL2");
        ConnectionResult result = connection.execCommand("echo hello", BecomeContext.empty(), env);

        assertEquals(0, result.exitCode());

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();

        // Verify environment variables are passed via -e
        assertTrue(command.contains("-e"));
        assertTrue(command.contains("VAR1=VAL1") || command.contains("VAR2=VAL2"));
    }

    @Test
    void testExecCommandNativeBecome() throws Exception {
        Process mockProcess = createMockProcess(0, "root_user\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        BecomeContext become = new BecomeContext(true, "runas", "root", null, null);
        ConnectionResult result = connection.execCommand("whoami", become, null);

        assertEquals(0, result.exitCode());

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();
        assertEquals(List.of("docker", "exec", "-u", "root", "my-container", "/bin/sh", "-c", "whoami"), command);
    }

    @Test
    void testExecCommandSudoBecome() throws Exception {
        Process mockProcess = createMockProcess(0, "root_user\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        BecomeContext become = new BecomeContext(true, "sudo", "root", null, null);
        ConnectionResult result = connection.execCommand("whoami", become, null);

        assertEquals(0, result.exitCode());

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();

        // Should include sudo wrapper inside the /bin/sh -c command
        assertTrue(command.contains("my-container"));
        String execCmd = command.get(command.size() - 1);
        assertTrue(execCmd.contains("sudo -H -S -n -p BECOME-PROMPT -u root"));
        assertTrue(execCmd.contains("whoami"));
    }

    @Test
    void testExecCommandSuBecome() throws Exception {
        Process mockProcess = createMockProcess(0, "root_user\n", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        BecomeContext become = new BecomeContext(true, "su", "root", null, null);
        ConnectionResult result = connection.execCommand("whoami", become, null);

        assertEquals(0, result.exitCode());

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();

        String execCmd = command.get(command.size() - 1);
        assertTrue(execCmd.contains("su root -c"));
        assertTrue(execCmd.contains("whoami"));
    }

    @Test
    void testPutFile() throws Exception {
        Process mockProcess = createMockProcess(0, "", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        Path localPath = Path.of("local_file.txt");
        assertDoesNotThrow(() -> connection.putFile(localPath, "/remote/path.txt"));

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();
        assertEquals(List.of("docker", "cp", localPath.toAbsolutePath().toString(), "my-container:/remote/path.txt"), command);
    }

    @Test
    void testFetchFile() throws Exception {
        Process mockProcess = createMockProcess(0, "", "");
        TestableDockerConnection connection = new TestableDockerConnection("my-container", "my-user", mockProcess);

        Path localPath = Path.of("local_dest.txt");
        assertDoesNotThrow(() -> connection.fetchFile("/remote/path.txt", localPath));

        List<ProcessBuilder> captured = connection.getCapturedProcessBuilders();
        assertEquals(1, captured.size());
        List<String> command = captured.get(0).command();
        assertEquals(List.of("docker", "cp", "my-container:/remote/path.txt", localPath.toAbsolutePath().toString()), command);
    }

    @Test
    void testDefaultConnectionFactoryIntegration() {
        DefaultConnectionFactory factory = new DefaultConnectionFactory();
        Host host = new Host("test-container", null);

        Map<String, Object> variables = new HashMap<>();
        variables.put("ansible_connection", "docker");
        variables.put("ansible_host", "my-container");
        variables.put("ansible_user", "my-user");

        Connection conn = factory.createConnection(host, variables);
        assertNotNull(conn);
        assertTrue(conn instanceof DockerConnection);

        DockerConnection dockerConn = (DockerConnection) conn;
        assertEquals("my-container", dockerConn.getContainerName());
        assertEquals("my-user", dockerConn.getDockerUser());
    }
}
