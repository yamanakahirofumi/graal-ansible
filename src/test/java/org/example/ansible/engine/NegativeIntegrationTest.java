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
        play = new Play("Negative Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testMissingMandatoryParameter() {
        // file module requires 'path' (or aliases like 'dest')
        Task task = new Task("Missing path", "file", Map.of("state", "touch"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should fail when mandatory parameter is missing");
        assertTrue(result.message().contains("path") || result.message().contains("required"), "Error message should mention 'path' or 'required'");
    }

    @Test
    void testInvalidParameterValue() {
        // file module state 'invalid' is not supported
        Task task = new Task("Invalid state", "file", Map.of(
                "path", "/tmp/invalid.txt",
                "state", "invalid_state_value"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should fail with invalid state");
    }

    @Test
    void testCommandFailure() {
        Task task = new Task("Run false", "command", Map.of("_raw_params", "false"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success());
        assertNotEquals(0, result.data().get("rc"));
    }

    @Test
    void testCopyNonExistentSource() {
        Task task = new Task("Copy missing", "copy", Map.of(
                "src", "/non/existent/path/file.txt",
                "dest", "/tmp/dest.txt"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertFalse(result.success(), "Copy should fail if source does not exist");
    }
}
