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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskControlIntegrationTest {

    private TaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private VariableManager vm;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
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

    private Task createTask(String name, String action, Map<String, Object> args, Object failedWhen, Object changedWhen, boolean ignoreErrors) {
        return new Task(name, action, args, Map.of(), null, null, null, Map.of(), List.of(),
                failedWhen, changedWhen, ignoreErrors,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null);
    }

    private Task createUntilTask(String name, String action, Map<String, Object> args, String register, Object until, int retries, int delay) {
        return new Task(name, action, args, Map.of(), null, register, null, Map.of(), List.of(),
                null, null, false,
                until, retries, delay, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null);
    }

    @Test
    void testFailedWhenImplicitAnd() {
        // failed_when with list: all must be true to fail
        Play play = new Play("failed_when play", "all", List.of(
                createTask("fail if both true", "debug", Map.of("msg", "hello"), List.of("1 == 1", "2 == 2"), null, true),
                createTask("not fail if one false", "debug", Map.of("msg", "hello"), List.of("1 == 1", "2 == 3"), null, false)
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertFalse(hostResults.get(0).success(), "Task 1 should fail because all conditions are met");
        assertTrue(hostResults.get(1).success(), "Task 2 should succeed because one condition is false");
    }

    @Test
    void testChangedWhenImplicitAnd() {
        // changed_when with list: all must be true to change
        Play play = new Play("changed_when play", "all", List.of(
                createTask("change if both true", "debug", Map.of("msg", "hello"), null, List.of("1 == 1", "2 == 2"), false),
                createTask("not change if one false", "debug", Map.of("msg", "hello"), null, List.of("1 == 1", "2 == 3"), false)
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertTrue(hostResults.get(0).changed(), "Task 1 should be changed");
        assertFalse(hostResults.get(1).changed(), "Task 2 should NOT be changed");
    }

    @Test
    void testUntilRetries() {
        Path counterFile = tempDir.resolve("counter.txt");

        Play play = new Play("until play", "all", List.of(
                createUntilTask("until task", "shell", Map.of("_raw_params", "echo 'x' >> " + counterFile + " && wc -l < " + counterFile),
                        "shell_out", "shell_out.stdout | trim == '3'", 5, 1)
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        TaskResult result = hostResults.get(0);
        assertTrue(result.success(), "Until task should eventually succeed: " + result.message());

        Map<String, Object> data = result.data();
        assertEquals(3, ((Number)data.get("attempts")).intValue(), "Should have taken 3 attempts");
        assertTrue(data.containsKey("results"), "Should contain results of all attempts");
        List<?> attemptsList = (List<?>) data.get("results");
        assertEquals(3, attemptsList.size());
    }

    @Test
    void testUntilFailure() {
        Play play = new Play("until failure play", "all", List.of(
                createUntilTask("fail until", "command", Map.of("_raw_params", "echo 'hello'"),
                        "cmd_out", "1 == 2", 3, 1)
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        TaskResult result = hostResults.get(0);
        assertFalse(result.success(), "Until task should fail if condition never met");
        assertTrue(result.message().contains("Until condition not met"), "Message should indicate until failure");
        assertEquals(3, ((Number)result.data().get("attempts")).intValue());
    }

    @Test
    void testPauseModule() {
        long start = System.currentTimeMillis();
        Task task = createTask("test pause", "pause", Map.of("seconds", 1), null, null, false);
        TaskResult result = taskExecutor.execute(task, org.example.ansible.connection.BecomeContext.empty(), new LocalConnection(), Map.of());
        long end = System.currentTimeMillis();

        assertTrue(result.success(), "Pause module failed: " + result.message());
        assertTrue((end - start) >= 1000, "Pause should have waited at least 1 second. Actual: " + (end - start) + "ms");
    }

    @Test
    void testMetaNoopModule() {
        Task task = createTask("test meta noop", "meta", Map.of("_raw_params", "noop"), null, null, false);
        TaskResult result = taskExecutor.execute(task, org.example.ansible.connection.BecomeContext.empty(), new LocalConnection(), Map.of());

        assertTrue(result.success(), "Meta noop failed: " + result.message());
        assertEquals("noop", result.data().get("meta"));
    }
}
