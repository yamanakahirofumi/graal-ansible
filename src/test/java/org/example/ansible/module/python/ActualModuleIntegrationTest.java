package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.SshConnection;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test using actual ansible-core modules.
 * Now using Testcontainers to verify target state.
 */
@Testcontainers
@EnabledOnOs(OS.LINUX)
class ActualModuleIntegrationTest {

    @Container
    private GenericContainer<?> targetNode = new GenericContainer<>(DockerImageName.parse("mokojarasi/test-python-sshd:latest"))
            .withExposedPorts(22)
            .withEnv("USER_PASSWORD", "testuser")
            .withEnv("USER_NAME", "testuser")
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("SUDO_ACCESS", "true")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;
    private SshConnection connection;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connection = new SshConnection(
                targetNode.getHost(),
                targetNode.getMappedPort(22),
                "testuser",
                "testuser"
        );
        connection.connect();
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testActualPingModule() {
        taskExecutor.registerModule("ping", new PythonModule("ping"));

        Task task = new Task("test_ping", "ping", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("pong", result.data().get("ping"));
    }

    @Test
    void testActualFileModule() {
        taskExecutor.registerModule("file", new PythonModule("file"));

        String remotePath = "/tmp/touch-test.txt";
        Task task = new Task("test_file", "file", Map.of(
                "path", remotePath,
                "state", "touch"
        ));

        // Now we use the actual SSH connection to execute the task on targetNode.
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed(), "File should have been created (changed=true)");

        // Verify state on target node using SSH
        var execResult = connection.execCommand("ls " + remotePath, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "File should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualStatModule() {
        taskExecutor.registerModule("stat", new PythonModule("stat"));

        String remotePath = "/tmp/stat-test.txt";
        // Setup state using SSH
        connection.execCommand("sh -c \"echo 'test data' > " + remotePath + "\"", BecomeContext.empty(), null);

        Task task = new Task("test_stat", "stat", Map.of(
                "path", remotePath
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> stat = (Map<String, Object>) result.data().get("stat");
        assertNotNull(stat);
        assertTrue((Boolean) stat.get("exists"));
    }

    @Test
    void testActualCopyModule() throws IOException {
        taskExecutor.registerModule("copy", new PythonModule("copy"));

        Path localSrcFile = tempDir.resolve("copy-src.txt");
        String content = "Hello from Actual Copy Module (src)";
        Files.writeString(localSrcFile, content);

        String remoteSrcPath = "/tmp/copy-src.txt";
        connection.putFile(localSrcFile, remoteSrcPath);

        String remoteDestPath = "/tmp/copy-test.txt";
        Task task = new Task("test_copy", "copy", Map.of(
                "src", remoteSrcPath,
                "dest", remoteDestPath,
                "remote_src", true
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        // Verify state on target node using SSH
        var execResult = connection.execCommand("cat " + remoteDestPath, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode());
        assertEquals(content, execResult.stdout().trim());
    }

    @Test
    void testActualDebugModule() {
        taskExecutor.registerModule("debug", new PythonModule("debug"));

        Task task = new Task("test_debug", "debug", Map.of("msg", "Hello from Actual Debug Module"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("Hello from Actual Debug Module", result.data().get("msg"));
    }

    @Test
    void testActualSetupModule() {
        taskExecutor.registerModule("setup", new PythonModule("setup"));

        Task task = new Task("test_setup", "setup", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertTrue(facts.containsKey("ansible_os_family"));
    }

    @Test
    void testActualCommandModule() {
        taskExecutor.registerModule("command", new PythonModule("command"));

        Task task = new Task("test_command", "command", Map.of("_raw_params", "echo hello_command"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("hello_command", ((String) result.data().get("stdout")).trim());
    }

    @Test
    void testActualShellModule() {
        taskExecutor.registerModule("shell", new PythonModule("shell"));

        Task task = new Task("test_shell", "shell", Map.of("_raw_params", "echo 'line1\nline2' | grep line2"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("line2", ((String) result.data().get("stdout")).trim());
    }

    @Test
    void testActualLineInFileModule() {
        taskExecutor.registerModule("lineinfile", new PythonModule("lineinfile"));

        String remotePath = "/tmp/lineinfile-test.txt";
        connection.execCommand("echo \"initial line\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_lineinfile", "lineinfile", Map.of(
                "path", remotePath,
                "line", "new line added"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        assertTrue(execResult.stdout().contains("new line added"));
    }

    @Test
    void testActualReplaceModule() {
        taskExecutor.registerModule("replace", new PythonModule("replace"));

        String remotePath = "/tmp/replace-test.txt";
        connection.execCommand("echo \"Hello World\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_replace", "replace", Map.of(
                "path", remotePath,
                "regexp", "World",
                "replace", "Ansible"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        assertEquals("Hello Ansible", execResult.stdout().trim());
    }
}
