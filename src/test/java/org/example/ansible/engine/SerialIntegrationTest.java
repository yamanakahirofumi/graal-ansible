package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SerialIntegrationTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");
        Group all = new Group("all", List.of(host1, host2, host3), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @Test
    void testSerialExecutionBatching() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        Task task2 = new Task("task 2", "ping", Map.of());

        // serial: 2 means batch 1: [host1, host2], batch 2: [host3]
        Play play = new Play(
                "test play", "all", List.of(task1, task2), Map.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), null, "linear", 2
        );
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        InOrder inOrder = inOrder(taskExecutor);

        // Batch 1: host1, host2
        // Task 1 on host1, then host2 (or vice versa, but sequentially in batch)
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host2")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        // Task 2 on host1, then host2
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task2), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host2")), eq(task2), any(), anyBoolean(), any(), any(), any(), any(), any(), any());

        // Batch 2: host3
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host3")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host3")), eq(task2), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSerialPercentage() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());

        // 3 hosts, 50% = floor(1.5) = 1. Batches: [host1], [host2], [host3]
        Play play = new Play(
                "test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), null, "linear", "50%"
        );
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Verify 3 separate executions for task1
        verify(taskExecutor, times(3)).execute(eq(play), any(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSerialList() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());

        // Batches: 1, 2. [host1], [host2, host3]
        Play play = new Play(
                "test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), null, "linear", List.of(1, 2)
        );
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        InOrder inOrder = inOrder(taskExecutor);
        // Batch 1: host1
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host1")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        // Batch 2: host2, host3
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host2")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
        inOrder.verify(taskExecutor).execute(eq(play), argThat(h -> h.name().equals("host3")), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }
}
