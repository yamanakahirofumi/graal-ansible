package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCallbackThreadSafeTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream outContent;
    private ITaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        taskExecutor = mock(ITaskExecutor.class);
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));
        tqm.addCallback(new DefaultCallback());

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");
        Group all = new Group("all", List.of(host1, host2, host3), Collections.emptyList(), Collections.emptyMap());
        inventory = new Inventory(all);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void testTaskHeaderDeduplicationWithFreeStrategy() {
        // Arrange
        Task task1 = new Task("test ping", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // Simulate some work to increase chance of concurrent callback calls
                    Thread.sleep(100);
                    return TaskResult.success(Map.of("ping", "pong"));
                });

        // Act
        tqm.executePlay(play, inventory, variableManager, results, false);

        // Assert
        String output = outContent.toString();
        int taskHeaderCount = countOccurrences(output, "TASK [test ping]");
        assertEquals(1, taskHeaderCount, "TASK header should be printed exactly once even with multiple hosts in free strategy");
    }

    private int countOccurrences(String str, String subStr) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(subStr, idx)) != -1) {
            count++;
            idx += subStr.length();
        }
        return count;
    }
}
