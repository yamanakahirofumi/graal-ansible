package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentPrecedenceTest {

    private TaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private VariableManager vm;
    private List<Map<String, String>> capturedEnvironments = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        // Register a mock module to capture environment
        taskExecutor.registerModule("capture_env", (args, become, context) -> {
            capturedEnvironments.add(new HashMap<>(TaskExecutor.getCurrentEnvironment()));
            return TaskResult.success(false, Map.of("captured", true));
        });

        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());
        Host host = new Host("localhost");
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        vm = new VariableManager(inventory, Map.of(), tempDir);
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testEnvironmentMerging() {
        // Play environment
        Map<String, Object> playEnv = Map.of("PLAY_VAR", "play", "OVERRIDE_VAR", "play");

        // Block environment
        Map<String, Object> blockEnv = Map.of("BLOCK_VAR", "block", "OVERRIDE_VAR", "block");

        // Task environment
        Map<String, Object> taskEnv = Map.of("TASK_VAR", "task", "OVERRIDE_VAR", "task");

        Task task = new Task("capture env task", "capture_env", Map.of());
        // Set environment at task level
        Task taskWithEnv = new Task(
                task.name(), task.action(), task.args(), task.vars(), task.when(), task.register(), task.loop(),
                task.notifications(), task.failedWhen(), task.changedWhen(), task.ignoreErrors(),
                task.until(), task.retries(), task.delay(), task.delegateTo(), task.delegateFacts(),
                task.runOnce(), task.ignoreUnreachable(), task.block(), task.rescue(), task.always(),
                task.become(), task.becomeMethod(), task.becomeUser(), task.becomeFlags(), task.checkMode(),
                taskEnv
        );

        Task block = new Task("block", null, Map.of(), Map.of(), null, null, null, List.of(),
                null, null, false, null, 3, 5, null, false, false, false,
                List.of(taskWithEnv), List.of(), List.of(),
                null, null, null, null, null, blockEnv);

        Play play = new Play(
                "play", "all", List.of(block), Map.of(), List.of(), List.of(), null, null, null, null, null, playEnv
        );

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        assertEquals(1, capturedEnvironments.size());
        Map<String, String> env = capturedEnvironments.get(0);

        assertEquals("play", env.get("PLAY_VAR"));
        assertEquals("block", env.get("BLOCK_VAR"));
        assertEquals("task", env.get("TASK_VAR"));
        assertEquals("task", env.get("OVERRIDE_VAR"), "Task environment should override Block and Play");
    }

    @Test
    void testNestedBlockEnvironmentMerging() {
        Map<String, Object> outerEnv = Map.of("OUTER", "outer", "VAR", "outer");
        Map<String, Object> innerEnv = Map.of("INNER", "inner", "VAR", "inner");

        Task task = new Task("capture env task", "capture_env", Map.of());

        Task innerBlock = new Task("inner block", null, Map.of(), Map.of(), null, null, null, List.of(),
                null, null, false, null, 3, 5, null, false, false, false,
                List.of(task), List.of(), List.of(),
                null, null, null, null, null, innerEnv);

        Task outerBlock = new Task("outer block", null, Map.of(), Map.of(), null, null, null, List.of(),
                null, null, false, null, 3, 5, null, false, false, false,
                List.of(innerBlock), List.of(), List.of(),
                null, null, null, null, null, outerEnv);

        Play play = new Play("play", "all", List.of(outerBlock));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        assertEquals(1, capturedEnvironments.size());
        Map<String, String> env = capturedEnvironments.get(0);

        assertEquals("outer", env.get("OUTER"));
        assertEquals("inner", env.get("INNER"));
        assertEquals("inner", env.get("VAR"));
    }
}
