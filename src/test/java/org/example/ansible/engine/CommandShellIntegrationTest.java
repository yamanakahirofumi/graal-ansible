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

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertEquals(0, result.data().get("rc"));
        assertEquals("hello", ((String)result.data().get("stdout")).trim());
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
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = isWindows ? "echo line2 | findstr line2" : "echo \"line1\nline2\" | grep line2";

        Task task = new Task("Run pipe", "shell", Map.of("_raw_params", command));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertEquals("line2", ((String)result.data().get("stdout")).trim());
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

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success(), "Should be failed due to failed_when");
        assertEquals("success", ((String)result.data().get("stdout")).trim());
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

        assertTrue(result.success(), result.message());
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

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        // Note: shell expansion of $MY_VAR might only work in 'shell' or if 'command' is executed via shell
        // Our 'command' implementation currently uses osHandler.getShellExecutable() which IS a shell.
        assertEquals("my_value", ((String)result.data().get("stdout")).trim());
    }
}
