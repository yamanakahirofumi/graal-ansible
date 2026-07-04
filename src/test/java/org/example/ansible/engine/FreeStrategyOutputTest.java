package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class FreeStrategyOutputTest {

    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private ByteArrayOutputStream outContent;

    @BeforeEach
    void setUp() {
        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));
        tqm.addCallback(new DefaultCallback());

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Group all = new Group("all", List.of(host1, host2), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);

        outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(System.out);
    }

    @Test
    void testFreeStrategyTaskHeaderDeduplication() {
        // Arrange
        Task task1 = new Task("test task", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        String output = outContent.toString();

        // Count occurrences of "TASK [test task]"
        int count = 0;
        int idx = 0;
        String header = "TASK [test task]";
        while ((idx = output.indexOf(header, idx)) != -1) {
            count++;
            idx += header.length();
        }

        assertEquals(1, count, "Task header should be printed exactly once in free strategy for multiple hosts");
    }
}
