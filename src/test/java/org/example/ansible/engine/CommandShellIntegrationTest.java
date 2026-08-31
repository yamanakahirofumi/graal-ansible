package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class CommandShellIntegrationTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of());
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testCommandSuccess() {
        Task task = new Task("Run echo", "command", Map.of("_raw_params", "echo hello"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message() + " Data: " + result.data());
        assertTrue(result.changed());
        assertEquals(0, result.data().get("rc"));
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("hello", stdout.trim());
    }

    @Test
    void testCommandFailure() {
        Task task = new Task("Run false", "command", Map.of("_raw_params", "false"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success());
        assertFalse(result.changed());
        assertNotEquals(0, result.data().get("rc"));
    }

    @Test
    void testShellWithPipe() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = isWindows ? "echo line2 | findstr line2" : "echo \"line1\nline2\" | grep line2";

        Task task = new Task("Run pipe", "shell", Map.of("_raw_params", command));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("line2", stdout.trim());
    }

    @Test
    void testFailedWhenCustomization() {
        // Task that would normally succeed but failed_when makes it fail
        // Use trim() in Jinja2 to be OS-agnostic regarding line endings
        Task task = new Task("Run echo with failed_when", "command",
            Map.of("_raw_params", "echo success"),
            Map.of(), null, null, null, List.of(), "stdout.trim() == 'success'", null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, Map.of());

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Should be failed due to failed_when");
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("success", stdout.trim());
    }

    @Test
    void testChangedWhenCustomization() {
        // Task that would normally be changed but changed_when makes it NOT changed
        Task task = new Task("Run echo with changed_when", "command",
            Map.of("_raw_params", "echo success"),
            Map.of(), null, null, null, List.of(), null, "false", false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, Map.of());

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Task failed: " + result.message() + " (Data: " + result.data() + ")");
        assertFalse(result.changed(), "Should not be changed due to changed_when: false");
    }

    @Test
    void testCommandWithEnv() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String echoVar = isWindows ? "echo %MY_VAR%" : "echo $MY_VAR";

        Map<String, String> env = Map.of("MY_VAR", "my_value");
        Task task = new Task("Run env check", "command",
            Map.of("_raw_params", echoVar),
            Map.of(), null, null, null, List.of(), null, null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, env);

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        // Note: shell expansion of $MY_VAR might only work in 'shell' or if 'command' is executed via shell
        // Our 'command' implementation currently uses osHandler.getShellExecutable() which IS a shell.
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("my_value", stdout.trim());
    }

    @Test
    void testSetupModule() {
        Task task = new Task("Gather facts", "setup", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should not be null");
        assertTrue(facts.containsKey("ansible_system") || facts.containsKey("ansible_architecture") || facts.containsKey("ansible_python_version"),
            "ansible_facts should contain system/architecture/python information");
    }

    @Test
    void testCommandAndShellCheckMode() {
        Task commandTask = new Task("Run command check mode", "command",
            Map.of("_raw_params", "echo check_mode_test"),
            Map.of(), null, null, null, List.of(), null, null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, true, Map.of());

        TaskResult commandResult = taskExecutor.execute(play, host, commandTask, variableManager, true, null, null, new LocalConnection(), null);
        assertTrue(commandResult.success(), "Command task execution failed: " + commandResult.message());

        Task shellTask = new Task("Run shell check mode", "shell",
            Map.of("_raw_params", "echo check_mode_test"),
            Map.of(), null, null, null, List.of(), null, null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, true, Map.of());

        TaskResult shellResult = taskExecutor.execute(play, host, shellTask, variableManager, true, null, null, new LocalConnection(), null);
        assertTrue(shellResult.success(), "Shell task execution failed: " + shellResult.message());
    }

    @Test
    void testCommandCreatesAndRemoves() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        Path existingFile = subDir.resolve("exists.txt");
        Files.writeString(existingFile, "content");

        // 1. test creates (file exists -> task skipped / changed=false)
        Task createsTask = new Task("Run echo when file exists", "command", Map.of(
                "_raw_params", "echo should_not_run",
                "creates", existingFile.toAbsolutePath().toString()
        ));
        TaskResult createsResult = taskExecutor.execute(play, host, createsTask, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(createsResult.success(), "createsResult failed: " + createsResult.message());
        assertFalse(createsResult.changed(), "Task with creates should report changed=false when target file exists");

        // 2. test removes (file does not exist -> task skipped / changed=false)
        Path nonExistentFile = subDir.resolve("missing.txt");
        Task removesTask = new Task("Run echo when file does not exist", "command", Map.of(
                "_raw_params", "echo should_not_run",
                "removes", nonExistentFile.toAbsolutePath().toString()
        ));
        TaskResult removesResult = taskExecutor.execute(play, host, removesTask, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(removesResult.success(), removesResult.message());
        assertFalse(removesResult.changed(), "Task with removes should report changed=false when target file does not exist");
    }

    @Test
    void testCommandWithChdir() throws IOException {
        Path subDir = tempDir.resolve("chdir_dir");
        Files.createDirectories(subDir);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String pwdCommand = isWindows ? "cd" : "pwd";

        Task task = new Task("Run pwd with chdir", "command", Map.of(
                "_raw_params", pwdCommand,
                "chdir", subDir.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message() + " Data: " + result.data());
        assertTrue(result.changed());

        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");

        Path expectedPath = subDir.toRealPath();
        Path actualPath = Path.of(stdout.trim()).toRealPath();
        assertEquals(expectedPath, actualPath, "Working directory should match chdir parameter");
    }
}
