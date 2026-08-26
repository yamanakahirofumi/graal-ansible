package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinearStrategyTest {

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
    void testLinearStrategyExecutionOrder() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        Task task2 = new Task("task 2", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertEquals(2, results.get("host1").size());
        assertEquals(2, results.get("host2").size());

        // Verify order: task1 on all hosts, then task2 on all hosts
        // (In our implementation it's task by task)
        verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host2")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task2), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host2")), eq(task2), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testLinearStrategyPreTasksAndPostTasks() {
        Task preTask = new Task("pre task", "ping", Map.of());
        Task mainTask = new Task("main task", "ping", Map.of());
        Task postTask = new Task("post task", "ping", Map.of());

        Play play = new Play(
                "play with pre/post", "all", List.of(mainTask), Map.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(preTask), List.of(postTask),
                null, null, null, null, null, null, List.of(), null, "linear", null, null, null
        );
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        tqm.executePlay(play, inventory, variableManager, results, false);

        assertEquals(3, results.get("host1").size());
        assertEquals(3, results.get("host2").size());
        verify(taskExecutor, times(6)).execute(eq(play), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testLinearStrategySerialBatches() {
        Host h1 = new Host("host1");
        Host h2 = new Host("host2");
        Host h3 = new Host("host3");
        Group all = new Group("all", List.of(h1, h2, h3), Collections.emptyList(), Collections.emptyMap());
        Inventory inv = new Inventory(all);

        Task task = new Task("batch task", "ping", Map.of());
        Play play = new Play(
                "serial play", "all", List.of(task), Map.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), null, "linear", 1, null, null
        );
        VariableManager variableManager = new VariableManager(inv, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        tqm.executePlay(play, inv, variableManager, results, false);

        assertEquals(1, results.get("host1").size());
        assertEquals(1, results.get("host2").size());
        assertEquals(1, results.get("host3").size());
        verify(taskExecutor, times(3)).execute(eq(play), any(), eq(task), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testLinearStrategyIgnoreUnreachable() {
        Task task = new Task("unreachable task", "ping", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, true, List.of(), List.of(), List.of(),
                null, null, null, null, null, null); // ignoreUnreachable = true

        Play play = new Play("test play", "all", List.of(task), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new UnreachableException("Host unreachable"));

        tqm.executePlay(play, inventory, variableManager, results, false);

        assertTrue(results.containsKey("host1"));
        assertTrue(results.get("host1").get(0).isUnreachable());
        assertEquals("Host unreachable", results.get("host1").get(0).message());
    }

    @Test
    void testLinearStrategyTagFiltering() {
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

        Play play = new Play("tag play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        tqm.executePlay(play, inventory, variableManager, results, false, List.of("web"), Collections.<String>emptyList(), null);

        assertEquals(2, results.get("host1").size());
        assertTrue(results.get("host1").get(0).success());
        assertTrue(results.get("host1").get(1).isSkipped());
    }
}
