package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SerialIntegrationTest {

    private ITaskExecutor taskExecutor;
    private ConnectionFactory connectionFactory;
    private TaskQueueManager tqm;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        connectionFactory = mock(ConnectionFactory.class);
        tqm = new TaskQueueManager(taskExecutor, connectionFactory);

        when(connectionFactory.createConnection(any(), any())).thenAnswer(invocation -> {
            Connection conn = mock(Connection.class);
            return conn;
        });

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new TaskResult(true, false, "OK", Map.of("stdout", "ok")));
    }

    @Test
    void testSerialInteger() {
        List<Host> hosts = List.of(new Host("h1"), new Host("h2"), new Host("h3"), new Host("h4"));
        Group all = new Group("all", hosts, List.of(), Map.of());
        Inventory inventory = new Inventory(all);
        VariableManager vm = new VariableManager(inventory, Map.of());

        Task task = new Task("Test Task", "ping", Map.of(), Map.of());
        Play play = new Play("Test Play", "all", List.of(task), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, 2, "linear");

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        // Verify task executed for all 4 hosts
        verify(taskExecutor, times(4)).execute(eq(play), any(), eq(task), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSerialPercentage() {
        List<Host> hosts = List.of(new Host("h1"), new Host("h2"), new Host("h3"), new Host("h4"));
        Group all = new Group("all", hosts, List.of(), Map.of());
        Inventory inventory = new Inventory(all);
        VariableManager vm = new VariableManager(inventory, Map.of());

        Task task = new Task("Test Task", "ping", Map.of(), Map.of());
        // 50% of 4 is 2
        Play play = new Play("Test Play", "all", List.of(task), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "50%", "linear");

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        verify(taskExecutor, times(4)).execute(eq(play), any(), eq(task), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAnsiblePlayBatchMagicVariable() {
        List<Host> hosts = List.of(new Host("h1"), new Host("h2"), new Host("h3"));
        Group all = new Group("all", hosts, List.of(), Map.of());
        Inventory inventory = new Inventory(all);
        VariableManager vm = new VariableManager(inventory, Map.of());

        // We use a task that will let us capture the variables passed to it
        Task task = new Task("Test Task", "ping", Map.of(), Map.of());
        Play play = new Play("Test Play", "all", List.of(task), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, 2, "linear");

        // Re-mock to capture the variables resolved during execution
        reset(taskExecutor);
        List<Map<String, Object>> capturedVars = new ArrayList<>();
        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    VariableManager vman = invocation.getArgument(3);
                    Play p = invocation.getArgument(0);
                    Host h = invocation.getArgument(1);
                    Task t = invocation.getArgument(2);
                    capturedVars.add(vman.getAllVariables(p, h, t, null));
                    return new TaskResult(true, false, "OK", new HashMap<>());
                });

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        assertEquals(3, capturedVars.size());

        // Batch 1: h1, h2
        List<String> batch1 = (List<String>) capturedVars.get(0).get("ansible_play_batch");
        assertEquals(2, batch1.size());
        assertTrue(batch1.contains("h1"));
        assertTrue(batch1.contains("h2"));

        List<String> batch1_2 = (List<String>) capturedVars.get(1).get("ansible_play_batch");
        assertEquals(2, batch1_2.size());
        assertTrue(batch1_2.contains("h1"));
        assertTrue(batch1_2.contains("h2"));

        // Batch 2: h3
        List<String> batch2 = (List<String>) capturedVars.get(2).get("ansible_play_batch");
        assertEquals(1, batch2.size());
        assertTrue(batch2.contains("h3"));
    }
}
