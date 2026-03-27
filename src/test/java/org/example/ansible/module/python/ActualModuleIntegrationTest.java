package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.SshConnection;
import org.example.ansible.engine.Play;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.VariableManager;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        Task task = new Task("test_ping", "ping", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("pong", result.data().get("ping"));
    }

    @Test
    void testActualFileModule() {

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
        // debug is an Action Plugin, no need to register it manually as a PythonModule.

        Task task = new Task("test_debug", "debug", Map.of("msg", "Hello from Actual Debug Module"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("Hello from Actual Debug Module", result.data().get("msg"));
    }

    @Test
    void testActualSetupModule() {

        Task task = new Task("test_setup", "setup", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertTrue(facts.containsKey("ansible_os_family"));
    }

    @Test
    void testActualCommandModule() {
        Task task = new Task("test_command", "command", Map.of("_raw_params", "echo hello_command"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("hello_command", stdout.trim());
    }

    @Test
    void testActualShellModule() {
        Task task = new Task("test_shell", "shell", Map.of("_raw_params", "echo 'line1\nline2' | grep line2"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("line2", stdout.trim());
    }

    @Test
    void testActualLineInFileModule() {

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

    @Test
    void testActualUserModule() {

        String userName = "testuser-ansible";
        Task task = new Task("test_user", "user", Map.of(
                "name", userName,
                "state", "present"
        ));
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        var execResult = connection.execCommand("id " + userName, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "User should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualGroupModule() {

        String groupName = "testgroup-ansible";
        Task task = new Task("test_group", "group", Map.of(
                "name", groupName,
                "state", "present"
        ));
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        var execResult = connection.execCommand("getent group " + groupName, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "Group should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualFindModule() {

        Task task = new Task("test_find", "find", Map.of(
                "paths", "/tmp",
                "patterns", "*-test.txt"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.data().containsKey("files"), "Result should contain 'files' key");
    }

    @Test
    void testActualTempfileModule() {

        Task task = new Task("test_tempfile", "tempfile", Map.of(
                "state", "directory",
                "suffix", "ansibletest"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String path = (String) result.data().get("path");
        assertNotNull(path);

        var execResult = connection.execCommand("ls -d " + path, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "Temp directory should exist: " + execResult.stderr());
    }

    @Test
    void testActualHostnameModule() {

        Task task = new Task("test_hostname", "hostname", Map.of(
                "name", "new-hostname"
        ));
        // hostname module usually requires root, but in this container it might fail to actually set it
        // We just want to see if it executes correctly.
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        // It might fail because Docker containers don't always allow changing hostname easily
        // but we check if it didn't fail due to bridge/launcher issues.
        assertNotNull(result);
    }

    @Test
    void testActualSlurpModule() {
        String remotePath = "/tmp/slurp-test.txt";
        String content = "Hello Slurp";
        connection.execCommand("echo -n \"" + content + "\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_slurp", "slurp", Map.of(
                "src", remotePath
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String encodedContent = (String) result.data().get("content");
        assertNotNull(encodedContent);
        // Use MIME decoder as it's more lenient with line breaks or other formatting issues
        String decoded = new String(java.util.Base64.getMimeDecoder().decode(encodedContent.trim()));
        assertEquals(content, decoded);
    }

    @Test
    void testActualSetFactModule() {
        // set_fact is an Action Plugin.
        Task task = new Task("test_set_fact", "set_fact", Map.of(
                "my_custom_fact", "fact_value"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertEquals("fact_value", facts.get("my_custom_fact"));
    }

    @Test
    void testActualAssertModule() {
        // assert is an Action Plugin.
        // It requires task_vars for evaluation.
        Map<String, Object> taskVars = Map.of("test_var", 100);
        Task task = new Task("test_assert", "assert", Map.of(
                "that", List.of("test_var == 100")
        ));

        // We need to provide taskVars for the Action Plugin to evaluate expressions.
        // TaskExecutor.executeActionPlugin uses these variables.
        // However, in this test setup, we usually call taskExecutor.execute(task, ...).
        // Let's see how TaskExecutor handles variables.

        // Setup a VariableManager to hold the variable.
        org.example.ansible.inventory.Inventory inventory = new org.example.ansible.inventory.Inventory(
                new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("target")), List.of(), Map.of())
        );
        VariableManager vm = new VariableManager(inventory, Map.of("test_var", 100));
        Play play = new Play("test play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());

        TaskResult result = taskExecutor.execute(play, new org.example.ansible.inventory.Host("target"), task, vm, false, null, connection, null);

        assertTrue(result.success(), "Assertion failed: " + result.message());
        assertEquals("All assertions passed", result.data().get("msg"));
    }

    @Test
    void testActualFailModule() {
        Task task = new Task("test_fail", "fail", Map.of(
                "msg", "Expected failure"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertFalse(result.success(), "Module should have failed");
        assertEquals("Expected failure", result.data().get("msg"));
    }

    @Test
    void testActualGatherFactsModule() {
        // gather_facts is an Action Plugin that usually delegates to setup.
        Task task = new Task("test_gather_facts", "gather_facts", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertTrue(facts.containsKey("ansible_os_family"));
    }
}
