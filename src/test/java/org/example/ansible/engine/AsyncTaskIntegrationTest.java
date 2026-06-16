package org.example.ansible.engine;

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

import static org.junit.jupiter.api.Assertions.*;

class AsyncTaskIntegrationTest {

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
    void testAsyncWithPolling() {
        // Run a command that takes a bit of time (sleep 1)
        Task task = new Task(
                "Async sleep",
                "command",
                Map.of("_raw_params", "sleep 1"),
                Map.of(), null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null,
                10, 1 // async: 10, poll: 1
        );

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message() + " Data: " + result.data());
        assertTrue(result.changed());
        // Since we are checking immediately after TaskExecutor.execute returns,
        // for poll > 0, TaskExecutor already waited for completion.
        assertEquals(1, ((Number)result.data().getOrDefault("finished", 0)).intValue());
        assertEquals(0, ((Number)result.data().getOrDefault("rc", -1)).intValue());
    }

    @Test
    void testAsyncFireAndForget() throws InterruptedException {
        // Run a command that takes a bit of time (sleep 2)
        Task task = new Task(
                "Async sleep fire and forget",
                "command",
                Map.of("_raw_params", "sleep 2"),
                Map.of(), null, "async_res", null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null,
                10, 0 // async: 10, poll: 0
        );

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success());
        // For poll: 0, it should return immediately with finished: 0
        assertEquals(0, ((Number)result.data().getOrDefault("finished", 1)).intValue());
        String jid = (String) result.data().get("ansible_job_id");
        assertNotNull(jid);

        // Update variable manager as TQM would do
        variableManager.registerVariable(host.name(), "async_res", result.data());

        // Check status using async_status
        Task statusTask = new Task("Check status", "async_status", Map.of("jid", jid));

        // Wait and check multiple times
        boolean finished = false;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1000);
            TaskResult statusResult = taskExecutor.execute(play, host, statusTask, variableManager, false, null, null, new LocalConnection(), null);
            assertTrue(statusResult.success());
            Object finishedObj = statusResult.data().get("finished");
            if (finishedObj instanceof Number && ((Number)finishedObj).intValue() == 1) {
                finished = true;
                assertEquals(0, ((Number)statusResult.data().getOrDefault("rc", -1)).intValue());
                break;
            }
        }

        assertTrue(finished, "Job should be finished by now");
    }
}
