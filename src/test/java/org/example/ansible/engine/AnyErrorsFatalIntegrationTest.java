package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class AnyErrorsFatalIntegrationTest {

    static class MockTaskExecutor extends TaskExecutor {
        Map<String, TaskResult> forcedResults = new HashMap<>();
        List<Task> executedTasks = new ArrayList<>();

        @Override
        public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, org.example.ansible.connection.Connection connection, org.example.ansible.connection.ConnectionFactory connectionFactory) {
            executedTasks.add(task);
            return forcedResults.getOrDefault(host.name() + ":" + task.name(), TaskResult.success(Map.of("changed", false)));
        }
    }

    @Test
    public void testAnyErrorsFatalAtPlayLevel() {
        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("host1", "all");
        inventory.addHostToGroup("host2", "all");
        VariableManager vm = new VariableManager(inventory, Map.of());

        Task task1 = new Task("Task 1", "ping", Map.of());
        Task task2 = new Task("Task 2", "ping", Map.of());

        Play play = new Play("Play with any_errors_fatal", "all", List.of(task1, task2),
                             new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                             null, null, null, null, null, null, new ArrayList<>(), true);

        taskExecutor.forcedResults.put("host1:Task 1", TaskResult.failure("Failed on host1"));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        // Verification:
        // Task 1 should run (on both hosts or until failure, depending on TQM iteration)
        assertTrue(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 1")));

        // Task 2 should NOT run because Task 1 failed on host1 and any_errors_fatal is true
        assertFalse(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 2")));
    }

    @Test
    public void testAnyErrorsFatalAtTaskLevel() {
        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("host1", "all");
        inventory.addHostToGroup("host2", "all");
        VariableManager vm = new VariableManager(inventory, Map.of());

        Task task1 = new Task("Task 1", "ping", Map.of(), Map.of(), null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), true);
        Task task2 = new Task("Task 2", "ping", Map.of());

        Play play = new Play("Play without any_errors_fatal", "all", List.of(task1, task2));

        taskExecutor.forcedResults.put("host1:Task 1", TaskResult.failure("Failed on host1"));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        assertTrue(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 1")));
        assertFalse(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 2")));
    }

    @Test
    public void testWithoutAnyErrorsFatal() {
        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("host1", "all");
        inventory.addHostToGroup("host2", "all");
        VariableManager vm = new VariableManager(inventory, Map.of());

        Task task1 = new Task("Task 1", "ping", Map.of());
        Task task2 = new Task("Task 2", "ping", Map.of());

        Play play = new Play("Play without any_errors_fatal", "all", List.of(task1, task2));

        taskExecutor.forcedResults.put("host1:Task 1", TaskResult.failure("Failed on host1"));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        assertTrue(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 1")));
        // Task 2 should run on host2 (because host1 failed but any_errors_fatal is false)
        assertTrue(taskExecutor.executedTasks.stream().anyMatch(t -> t.name().equals("Task 2")));
    }
}
