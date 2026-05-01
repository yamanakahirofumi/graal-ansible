package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NegativeIntegrationTest {

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
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Negative Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testMissingRequiredArgument() {
        // 'file' module requires 'path'
        Task task = new Task("Missing path", "file", Map.of(
                "state", "touch"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should have failed due to missing 'path'");
        String msg = result.data().getOrDefault("msg", "").toString();
        assertTrue(msg.contains("missing") || msg.contains("path"),
                "Error message should mention missing parameter or 'path'. Message: " + result.message() + ", Data: " + result.data());
    }

    @Test
    void testInvalidArgumentType() {
        // For 'copy' module, 'dest' must be a string.
        Task task = new Task("Invalid type", "copy", Map.of(
                "dest", List.of("/tmp/invalid1", "/tmp/invalid2"),
                "content", "test"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should have failed due to invalid type (multi-element list) for 'dest'");
    }

    @Test
    void testNonExistentFileForSlurp() {
        Path nonExistent = tempDir.resolve("does-not-exist.txt");
        Task task = new Task("Slurp non-existent", "slurp", Map.of(
                "src", nonExistent.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Slurp should have failed for non-existent file");
        // Module usually returns failed=True and a message
        assertTrue(result.data().containsKey("msg"));
    }

    @Test
    void testCommandFailure() {
        Task task = new Task("Run invalid command", "command", Map.of(
                "_raw_params", "nonexistentcommand_xyz_123"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Command should have failed");
        assertNotEquals(0, result.data().get("rc"), "Return code should not be zero");
    }

    @Test
    void testInvalidJinjaTemplate() {
        // Jinja syntax error. Currently VariableResolver returns the string as is if it cannot render.
        Task task = new Task("Invalid jinja", "debug", Map.of(
                "msg", "hello {{ name"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertEquals("hello {{ name", result.data().get("msg"));
    }
}
