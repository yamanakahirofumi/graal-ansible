package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FreeStrategyTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Group all = new Group("all", List.of(host1, host2), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @Test
    void testFreeStrategyExecution() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        Task task2 = new Task("task 2", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertEquals(2, results.get("host1").size());
        assertEquals(2, results.get("host2").size());
        verify(taskExecutor, times(4)).execute(eq(play), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testFreeStrategyFailureIsolation() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        Task task2 = new Task("task 2", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        // host1 fails on task1, host2 succeeds on both
        when(taskExecutor.execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.failure("failed"));
        when(taskExecutor.execute(eq(play), argThat(h -> h.name().equals("host2")), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of()));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertEquals(1, results.get("host1").size(), "host1 should stop after first task failure");
        assertEquals(2, results.get("host2").size(), "host2 should continue despite host1 failure");
    }

    @Test
    void testFreeStrategyRunOnce() {
        // Arrange
        // Using correct constructor for Task (name, action, args, vars, when, register, loop, notifications, failedWhen, changedWhen, ignoreErrors,
        // until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
        // become, becomeMethod, becomeUser, becomeFlags, checkMode, environment)
        Task task1 = new Task("task 1", "ping", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, true, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null); // runOnce = true
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        int totalResults = results.values().stream().mapToInt(List::size).sum();
        assertEquals(1, totalResults, "Task with run_once should execute only once across all hosts");
        verify(taskExecutor, times(1)).execute(eq(play), any(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testFreeStrategyPreTasksAndPostTasks() {
        Task preTask = new Task("pre task", "ping", Map.of());
        Task mainTask = new Task("main task", "ping", Map.of());
        Task postTask = new Task("post task", "ping", Map.of());

        Play play = new Play(
                "play with pre/post", "all", List.of(mainTask), Map.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(preTask), List.of(postTask),
                null, null, null, null, null, null, List.of(), null, "free", null, null, null
        );
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        tqm.executePlay(play, inventory, variableManager, results, false);

        assertEquals(3, results.get("host1").size());
        assertEquals(3, results.get("host2").size());
        verify(taskExecutor, times(6)).execute(eq(play), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testFreeStrategyIgnoreUnreachable() {
        Task task = new Task("unreachable task", "ping", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, true, List.of(), List.of(), List.of(),
                null, null, null, null, null, null); // ignoreUnreachable = true

        Play play = new Play("test play", "all", List.of(task), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new UnreachableException("Host unreachable"));

        tqm.executePlay(play, inventory, variableManager, results, false);

        assertTrue(results.containsKey("host1"));
        assertTrue(results.get("host1").get(0).isUnreachable());
        assertEquals("Host unreachable", results.get("host1").get(0).message());
    }

    @Test
    void testFreeStrategyTagFiltering() {
        Task task1 = new Task(
                "tagged task", "ping", Map.of(), Map.of(), null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of("web")
        );
        Task task2 = new Task(
                "other task", "ping", Map.of(), Map.of(), null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of("db")
        );

        Play play = new Play("tag play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        tqm.executePlay(play, inventory, variableManager, results, false, List.of("web"), Collections.<String>emptyList(), null);

        assertEquals(2, results.get("host1").size());
        assertTrue(results.get("host1").get(0).success());
        assertTrue(results.get("host1").get(1).isSkipped());
    }
}
