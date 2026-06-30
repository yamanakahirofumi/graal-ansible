package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ThrottleIntegrationTest {

    @Test
    public void testThrottleInFreeStrategy() throws Exception {
        // Prepare inventory with 10 hosts
        List<Host> hostList = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            hostList.add(new Host("host" + i));
        }
        Group allGroup = new Group("all", hostList, Collections.emptyList(), Collections.emptyMap());
        Inventory inventory = new Inventory(allGroup);

        // Mock Connection and ConnectionFactory to simulate delay
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        AtomicInteger activeExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);

        when(connectionFactory.createConnection(any(Host.class), any())).thenAnswer(invocation -> {
            Connection conn = mock(Connection.class);
            when(conn.execCommand(any(), any(), any())).thenAnswer(execInvocation -> {
                int current = activeExecutions.incrementAndGet();
                synchronized (maxConcurrentExecutions) {
                    if (current > maxConcurrentExecutions.get()) {
                        maxConcurrentExecutions.set(current);
                    }
                }
                Thread.sleep(100); // Simulate some work
                activeExecutions.decrementAndGet();
                return new ConnectionResult("ok", "", 0);
            });
            return conn;
        });

        TaskExecutor taskExecutor = new TaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, connectionFactory);
        tqm.setForks(10); // Allow high parallelism normally

        // Play with throttle: 2
        Task task = new Task(
                "throttle task",
                "command",
                Map.of("_raw_params", "sleep 1"),
                new HashMap<>(), null, null, null, Map.of(), new ArrayList<>(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null,
                null, // max_fail_percentage
                2, // throttle
                0, 10
        );

        Play play = new Play("throttle play", "all", List.of(task), new HashMap<>(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free", null, null, null);

        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        tqm.executePlay(play, inventory, variableManager, results, false);

        // Max concurrent executions should be at most 2
        assertTrue(maxConcurrentExecutions.get() <= 2, "Max concurrent executions was " + maxConcurrentExecutions.get() + ", expected <= 2");
        assertTrue(maxConcurrentExecutions.get() > 0);
    }
}
