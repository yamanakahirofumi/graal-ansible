package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskQueueManagerTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));

        Host host1 = new Host("host1");
        Group all = new Group("all", List.of(host1), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @Test
    void testExecuteSimplePlay() {
        // Arrange
        Task task = new Task("test task", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(results.containsKey("host1"));
        assertEquals(1, results.get("host1").size());
        verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task), any(), eq(false), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testExecuteBlock() {
        // Arrange
        Task innerTask = new Task("inner task", "ping", Map.of());
        Task blockTask = new Task("block task", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerTask), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("test play", "all", List.of(blockTask));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(results.containsKey("host1"));
        assertEquals(1, results.get("host1").size());
        verify(taskExecutor).execute(eq(play), any(), eq(innerTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testBlockWhenSkipped() {
        // Arrange
        Task innerTask = new Task("inner task", "ping", Map.of());
        Task blockTask = new Task("block task", null, Map.of(), Map.of(), "false", null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerTask), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("test play", "all", List.of(blockTask));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(results.containsKey("host1"));
        assertEquals(1, results.get("host1").size());
        assertTrue((Boolean) results.get("host1").get(0).data().get("skipped"));
        verify(taskExecutor, never()).execute(any(), any(), eq(innerTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testBlockRescue() {
        // Arrange
        Task failingTask = new Task("failing task", "fail", Map.of());
        Task rescueTask = new Task("rescue task", "debug", Map.of());
        Task blockTask = new Task("block task", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(failingTask), List.of(rescueTask), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("test play", "all", List.of(blockTask));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(eq(play), any(), eq(failingTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.failure("failed"));
        when(taskExecutor.execute(eq(play), any(), eq(rescueTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of()));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        verify(taskExecutor).execute(eq(play), any(), eq(failingTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(taskExecutor).execute(eq(play), any(), eq(rescueTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testHandlerNotification() {
        // Arrange
        Task handlerTask = new Task("my handler", "debug", Map.of());
        Task task = new Task("test task", "ping", Map.of(), Map.of(), null, null, null, List.of("my handler"), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("test play", "all", List.of(task), Map.of(), List.of(), List.of(handlerTask), null, null, null, null, null, null);
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // First call (ping) returns changed=true
        when(taskExecutor.execute(eq(play), any(), eq(task), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(true, Map.of("changed", true)));
        // Second call (handler)
        when(taskExecutor.execute(eq(play), any(), eq(handlerTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of()));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        verify(taskExecutor).execute(eq(play), any(), eq(task), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        verify(taskExecutor).execute(eq(play), any(), eq(handlerTask), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testProcessGroupBy() {
        // Arrange
        Task task = new Task("group by task", "group_by", Map.of("key", "new_group"));
        Play play = new Play("test play", "all", List.of(task));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("add_group", "new_group")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertTrue(inventory.getGroupsMap().containsKey("new_group"));
        assertTrue(inventory.getGroupsMap().get("new_group").contains("host1"));
    }
}
