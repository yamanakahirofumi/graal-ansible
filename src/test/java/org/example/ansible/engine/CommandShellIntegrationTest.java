package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of());
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());

        // Register command and shell modules as they are registered in PlaybookCli
        taskExecutor.registerModule("command", (args, becomeContext, context) -> {
            String command = (String) args.get("_raw_params");
            if (command == null) command = (String) args.get("cmd");
            if (command == null) return TaskResult.failure("no command given");

            org.example.ansible.connection.Connection connection = TaskExecutor.getCurrentConnection();
            if (connection == null) connection = new LocalConnection();

            org.example.ansible.connection.ConnectionResult result = connection.execCommand(command, becomeContext, TaskExecutor.getCurrentEnvironment());
            Map<String, Object> data = new HashMap<>();
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("rc", result.exitCode());
            data.put("changed", result.exitCode() == 0);

            if (result.exitCode() != 0) {
                return new TaskResult(false, false, "Command failed with rc " + result.exitCode(), data);
            }
            return TaskResult.success(data);
        });

        taskExecutor.registerModule("shell", (args, becomeContext, context) -> {
            String command = (String) args.get("_raw_params");
            if (command == null) command = (String) args.get("cmd");
            if (command == null) return TaskResult.failure("no command given");

            org.example.ansible.connection.Connection connection = TaskExecutor.getCurrentConnection();
            if (connection == null) connection = new LocalConnection();

            org.example.ansible.connection.ConnectionResult result = connection.execCommand(command, becomeContext, TaskExecutor.getCurrentEnvironment());
            Map<String, Object> data = new HashMap<>();
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("rc", result.exitCode());
            data.put("changed", result.exitCode() == 0);

            if (result.exitCode() != 0) {
                return new TaskResult(false, false, "Shell command failed with rc " + result.exitCode(), data);
            }
            return TaskResult.success(data);
        });
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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals(0, result.data().get("rc"));
        assertEquals("hello\n", result.data().get("stdout"));
    }

    @Test
    void testCommandFailure() {
        Task task = new Task("Run false", "command", Map.of("_raw_params", "false"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success());
        assertFalse(result.changed());
        assertNotEquals(0, result.data().get("rc"));
    }

    @Test
    void testShellWithPipe() {
        // Skip on Windows if it doesn't support pipes in the same way, but LocalConnection uses OSHandler
        Task task = new Task("Run pipe", "shell", Map.of("_raw_params", "echo 'line1\nline2' | grep line2"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertEquals("line2\n", result.data().get("stdout"));
    }

    @Test
    void testFailedWhenCustomization() {
        // Task that would normally succeed but failed_when makes it fail
        Task task = new Task("Run echo with failed_when", "command",
            Map.of("_raw_params", "echo success"),
            Map.of(), null, null, null, List.of(), "stdout == 'success\n'", null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, Map.of());

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success(), "Should be failed due to failed_when");
        assertEquals("success\n", result.data().get("stdout"));
    }

    @Test
    void testChangedWhenCustomization() {
        // Task that would normally be changed but changed_when makes it NOT changed
        Task task = new Task("Run echo with changed_when", "command",
            Map.of("_raw_params", "echo success"),
            Map.of(), null, null, null, List.of(), null, "false", false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, Map.of());

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertFalse(result.changed(), "Should not be changed due to changed_when: false");
    }

    @Test
    void testCommandWithEnv() {
        Map<String, String> env = Map.of("MY_VAR", "my_value");
        Task task = new Task("Run env check", "command",
            Map.of("_raw_params", "echo $MY_VAR"),
            Map.of(), null, null, null, List.of(), null, null, false,
            null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
            null, null, null, null, null, env);

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        // Note: shell expansion of $MY_VAR might only work in 'shell' or if 'command' is executed via shell
        // Our 'command' implementation currently uses osHandler.getShellExecutable() which IS a shell.
        assertEquals("my_value\n", result.data().get("stdout"));
    }
}
