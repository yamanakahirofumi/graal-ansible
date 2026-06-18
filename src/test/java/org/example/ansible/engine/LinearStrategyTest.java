package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
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
}
