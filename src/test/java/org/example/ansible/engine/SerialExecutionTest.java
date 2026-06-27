package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SerialExecutionTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        connectionFactory = (host, vars) -> mock(Connection.class);
        tqm = new TaskQueueManager(taskExecutor, connectionFactory);

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");
        Group all = new Group("all", List.of(host1, host2, host3), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @Test
    void testSerialInteger() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: 2 for 3 hosts should result in batches of 2 and 1
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", 2);
        VariableManager variableManager = spy(new VariableManager(inventory, Collections.emptyMap()));
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        assertEquals(3, results.size());

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableManager, atLeastOnce()).setBatchContext(captor.capture());

        List<List<String>> allValues = captor.getAllValues();

        boolean foundBatch2 = false;
        boolean foundBatch1 = false;
        for (List<String> batch : allValues) {
            if (batch != null) {
                if (batch.size() == 2 && batch.contains("host1") && batch.contains("host2")) foundBatch2 = true;
                if (batch.size() == 1 && batch.contains("host3")) foundBatch1 = true;
            }
        }
        assertTrue(foundBatch2, "Batch of 2 hosts [host1, host2] not found in: " + allValues);
        assertTrue(foundBatch1, "Batch of 1 host [host3] not found in: " + allValues);
    }

    @Test
    void testSerialPercentage() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: 50% for 3 hosts should result in batches of 1 (floor(3 * 0.5) = 1, but min is 1)
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", "50%");
        VariableManager variableManager = spy(new VariableManager(inventory, Collections.emptyMap()));
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableManager, atLeastOnce()).setBatchContext(captor.capture());

        int batchesOf1 = 0;
        for (List<String> batch : captor.getAllValues()) {
            if (batch != null && batch.size() == 1) batchesOf1++;
        }
        // Should have 3 batches of size 1
        assertTrue(batchesOf1 >= 3, "Expected at least 3 batches of size 1, but got: " + batchesOf1);
    }

    @Test
    void testSerialList() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: [1, 2] for 3 hosts should result in batches of 1 and 2
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", List.of(1, 2));
        VariableManager variableManager = spy(new VariableManager(inventory, Collections.emptyMap()));
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(variableManager, atLeastOnce()).setBatchContext(captor.capture());

        boolean foundBatch1 = false;
        boolean foundBatch2 = false;
        for (List<String> batch : captor.getAllValues()) {
            if (batch != null) {
                if (batch.size() == 1 && batch.contains("host1")) foundBatch1 = true;
                if (batch.size() == 2 && batch.contains("host2") && batch.contains("host3")) foundBatch2 = true;
            }
        }
        assertTrue(foundBatch1, "Batch of 1 [host1] not found");
        assertTrue(foundBatch2, "Batch of 2 [host2, host3] not found");
    }

    @Test
    void testSerialWithHandlers() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of(), Map.of(), null, null, null, Map.of(), new ArrayList<>(List.of("handler1")), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null, 0, 10);
        Task handler1 = new Task("handler1", "debug", Map.of("msg", "Handling"));

        // serial: 1 for 2 hosts. Batch 1: host1, Batch 2: host2
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(handler1), null, null, null, null, null, null, List.of(), null, "linear", 1);
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(true, Map.of("changed", true)));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        // TaskExecutor should be called for:
        // 1. task1 on host1
        // 2. handler1 on host1
        // 3. task1 on host2
        // 4. handler1 on host2

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(taskExecutor, atLeast(4)).execute(any(), hostCaptor.capture(), taskCaptor.capture(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());

        List<Task> capturedTasks = taskCaptor.getAllValues();
        List<Host> capturedHosts = hostCaptor.getAllValues();

        List<Task> relevantTasks = new ArrayList<>();
        List<Host> relevantHosts = new ArrayList<>();
        for (int i = 0; i < capturedTasks.size(); i++) {
            Task t = capturedTasks.get(i);
            if (t != null && ("task 1".equals(t.name()) || "handler1".equals(t.name()))) {
                relevantTasks.add(t);
                relevantHosts.add(capturedHosts.get(i));
            }
        }

        // Use >= 4 because setup tasks might have been executed (though not in this mock setup, usually)
        assertTrue(relevantTasks.size() >= 4);

        // We look for the sequence: task 1 on host1, handler1 on host1, task 1 on host2, handler1 on host2
        int index = -1;
        for (int i = 0; i <= relevantTasks.size() - 4; i++) {
            if ("task 1".equals(relevantTasks.get(i).name()) && "host1".equals(relevantHosts.get(i).name()) &&
                "handler1".equals(relevantTasks.get(i+1).name()) && "host1".equals(relevantHosts.get(i+1).name()) &&
                "task 1".equals(relevantTasks.get(i+2).name()) && "host2".equals(relevantHosts.get(i+2).name()) &&
                "handler1".equals(relevantTasks.get(i+3).name()) && "host2".equals(relevantHosts.get(i+3).name())) {
                index = i;
                break;
            }
        }

        assertNotEquals(-1, index, "Expected sequence not found in: " + relevantTasks.stream().map(t -> t.name()).toList() + " on " + relevantHosts.stream().map(h -> h.name()).toList());
    }
}
