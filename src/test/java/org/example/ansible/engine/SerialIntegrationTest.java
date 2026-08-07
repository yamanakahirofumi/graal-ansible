package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SerialIntegrationTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private List<Host> hostList;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));

        hostList = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            hostList.add(new Host("host" + i));
        }
        Group all = new Group("all", hostList, Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @Test
    void testSerialInteger() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: 2 for 5 hosts -> batches: [host1, host2], [host3, host4], [host5]
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", 2);
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(taskExecutor, times(5)).execute(eq(play), hostCaptor.capture(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());

        List<Host> executedHosts = hostCaptor.getAllValues();
        Set<String> executedHostNames = new HashSet<>();
        for (Host h : executedHosts) {
            executedHostNames.add(h.name());
        }
        assertTrue(executedHostNames.contains("host1"));
        assertTrue(executedHostNames.contains("host2"));
        assertTrue(executedHostNames.contains("host3"));
        assertTrue(executedHostNames.contains("host4"));
        assertTrue(executedHostNames.contains("host5"));
    }

    @Test
    void testSerialPercentage() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: 40% for 5 hosts -> 5 * 0.4 = 2. batches: [host1, host2], [host3, host4], [host5]
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", "40%");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        verify(taskExecutor, times(5)).execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSerialList() {
        // Arrange
        Task task1 = new Task("task 1", "ping", Map.of());
        // serial: [1, 2] for 5 hosts -> batches: [host1], [host2, host3], [host4, host5]
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", List.of(1, 2));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        verify(taskExecutor, times(5)).execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAnsiblePlayBatch() {
        // Arrange
        Task task1 = new Task("task 1", "debug", Map.of("msg", "{{ ansible_play_batch }}"));

        List<Host> orderedHosts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            orderedHosts.add(new Host("host" + i));
        }
        Group all = new Group("all", orderedHosts, Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);

        // serial: 2 for 5 hosts -> batches: [host1, host2], [host3, host4], [host5]
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", 2);
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Use a real VariableResolver to evaluate magic variables
        VariableResolver resolver = new VariableResolver();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Play p = invocation.getArgument(0);
                    Host h = invocation.getArgument(1);
                    Task t = invocation.getArgument(2);
                    VariableManager vm = invocation.getArgument(3);
                    Map<String, Object> vars = vm.getAllVariables(p, h, t, null);
                    return TaskResult.success(Map.of("msg", vars.get("ansible_play_batch")));
                });

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        List<String> batch1 = new ArrayList<>((List<String>) results.get("host1").get(0).data().get("msg"));
        List<String> batch2 = new ArrayList<>((List<String>) results.get("host3").get(0).data().get("msg"));
        List<String> batch3 = new ArrayList<>((List<String>) results.get("host5").get(0).data().get("msg"));

        Collections.sort(batch1);
        Collections.sort(batch2);
        Collections.sort(batch3);

        assertEquals(List.of("host1", "host2"), batch1);
        assertEquals(List.of("host3", "host4"), batch2);
        assertEquals(List.of("host5"), batch3);
    }

    @Test
    void testHandlersFlushedPerBatch() {
        // Arrange
        // Using only 3 hosts for this test to match expected batching
        List<Host> threeHosts = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            threeHosts.add(new Host("host" + i));
        }
        Group all = new Group("all", threeHosts, Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);

        Task task1 = new Task("task 1", "ping", Map.of());
        task1.notifications().add("handler1");
        Task handler1 = new Task("handler1", "ping", Map.of());
        // serial: 2 for 3 hosts -> batches: [host1, host2], [host3]
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(handler1), null, null, null, null, null, null, List.of(), null, "linear", 2);
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("changed", true)));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        // Verify execution order:
        // task1 (host1)
        // task1 (host2)
        // handler1 (host1)
        // handler1 (host2)
        // task1 (host3)
        // handler1 (host3)
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(taskExecutor, times(6)).execute(eq(play), hostCaptor.capture(), taskCaptor.capture(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());

        List<Task> executedTasks = taskCaptor.getAllValues();
        List<Host> executedHosts = hostCaptor.getAllValues();

        assertEquals("task 1", executedTasks.get(0).name());
        assertEquals("host1", executedHosts.get(0).name());
        assertEquals("task 1", executedTasks.get(1).name());
        assertEquals("host2", executedHosts.get(1).name());
        assertEquals("handler1", executedTasks.get(2).name());
        assertEquals("host1", executedHosts.get(2).name());
        assertEquals("handler1", executedTasks.get(3).name());
        assertEquals("host2", executedHosts.get(3).name());
        assertEquals("task 1", executedTasks.get(4).name());
        assertEquals("host3", executedHosts.get(4).name());
        assertEquals("handler1", executedTasks.get(5).name());
        assertEquals("host3", executedHosts.get(5).name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSerialListDetailed() {
        // Arrange
        Task task1 = new Task("task 1", "debug", Map.of("msg", "{{ ansible_play_batch }}"));
        // 5 hosts. serial: [1, 2] -> Batch 1: [host1] (size 1). Batch 2: [host2, host3] (size 2). Batch 3: [host4, host5] (size 2, since 2 is the last element).
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", List.of(1, 2));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Play p = invocation.getArgument(0);
                    Host h = invocation.getArgument(1);
                    Task t = invocation.getArgument(2);
                    VariableManager vm = invocation.getArgument(3);
                    Map<String, Object> vars = vm.getAllVariables(p, h, t, null);
                    return TaskResult.success(Map.of("msg", vars.get("ansible_play_batch")));
                });

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        // We expect 5 total invocations
        ArgumentCaptor<Host> hostCaptor = ArgumentCaptor.forClass(Host.class);
        verify(taskExecutor, times(5)).execute(eq(play), hostCaptor.capture(), eq(task1), any(), anyBoolean(), any(), any(), any(), any(), any(), any());

        List<Host> executedHosts = hostCaptor.getAllValues();
        assertEquals("host1", executedHosts.get(0).name());
        assertEquals("host2", executedHosts.get(1).name());
        assertEquals("host3", executedHosts.get(2).name());
        assertEquals("host4", executedHosts.get(3).name());
        assertEquals("host5", executedHosts.get(4).name());

        // Dynamic ansible_play_batch verification
        List<String> batch1 = new ArrayList<>((List<String>) results.get("host1").get(0).data().get("msg"));
        List<String> batch2_h2 = new ArrayList<>((List<String>) results.get("host2").get(0).data().get("msg"));
        List<String> batch2_h3 = new ArrayList<>((List<String>) results.get("host3").get(0).data().get("msg"));
        List<String> batch3_h4 = new ArrayList<>((List<String>) results.get("host4").get(0).data().get("msg"));
        List<String> batch3_h5 = new ArrayList<>((List<String>) results.get("host5").get(0).data().get("msg"));

        Collections.sort(batch1);
        Collections.sort(batch2_h2);
        Collections.sort(batch2_h3);
        Collections.sort(batch3_h4);
        Collections.sort(batch3_h5);

        assertEquals(List.of("host1"), batch1);
        assertEquals(List.of("host2", "host3"), batch2_h2);
        assertEquals(List.of("host2", "host3"), batch2_h3);
        assertEquals(List.of("host4", "host5"), batch3_h4);
        assertEquals(List.of("host4", "host5"), batch3_h5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSerialListMixedAnsiblePlayBatch() {
        // Arrange
        Task task1 = new Task("task 1", "debug", Map.of("msg", "{{ ansible_play_batch }}"));
        // 5 hosts. serial: [1, "40%"] -> 40% of 5 is 2.
        // Batch 1: [host1] (size 1).
        // Batch 2: [host2, host3] (size 2).
        // Batch 3: [host4, host5] (size 2, since "40%" is the last element, resolved to 2).
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", List.of(1, "40%"));
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new HashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Play p = invocation.getArgument(0);
                    Host h = invocation.getArgument(1);
                    Task t = invocation.getArgument(2);
                    VariableManager vm = invocation.getArgument(3);
                    Map<String, Object> vars = vm.getAllVariables(p, h, t, null);
                    return TaskResult.success(Map.of("msg", vars.get("ansible_play_batch")));
                });

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        List<String> batch1 = new ArrayList<>((List<String>) results.get("host1").get(0).data().get("msg"));
        List<String> batch2 = new ArrayList<>((List<String>) results.get("host2").get(0).data().get("msg"));
        List<String> batch3 = new ArrayList<>((List<String>) results.get("host4").get(0).data().get("msg"));

        Collections.sort(batch1);
        Collections.sort(batch2);
        Collections.sort(batch3);

        assertEquals(List.of("host1"), batch1);
        assertEquals(List.of("host2", "host3"), batch2);
        assertEquals(List.of("host4", "host5"), batch3);
    }
}
