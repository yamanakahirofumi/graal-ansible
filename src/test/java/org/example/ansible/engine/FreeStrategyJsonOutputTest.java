package org.example.ansible.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreeStrategyJsonOutputTest {

    @Test
    void testFreeStrategyJsonOutput() throws Exception {
        // Arrange
        ITaskExecutor taskExecutor = mock(ITaskExecutor.class);
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> mock(Connection.class));
        JsonCallback jsonCallback = new JsonCallback();
        tqm.addCallback(jsonCallback);

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Group all = new Group("all", List.of(host1, host2), Collections.emptyList(), Collections.emptyMap());
        Inventory inventory = new Inventory(all);

        Task task1 = new Task("task 1", "ping", Map.of());
        Play play = new Play("test play", "all", List.of(task1), Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free");
        VariableManager variableManager = new VariableManager(inventory, Collections.emptyMap());
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        when(taskExecutor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(Map.of("ping", "pong")));

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        // Act
        try {
            tqm.executePlay(play, inventory, variableManager, results, false);
            // Stats are normally called at the end of playbook execution, but here we call it manually for the play
            Map<String, Map<String, Integer>> stats = Map.of(
                    "host1", Map.of("ok", 1),
                    "host2", Map.of("ok", 1)
            );
            jsonCallback.v2_playbook_on_stats(stats);
        } finally {
            System.setOut(originalOut);
        }

        // Assert
        String output = outContent.toString();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(output);

        assertTrue(root.has("plays"));
        JsonNode plays = root.get("plays");
        assertEquals(1, plays.size());

        JsonNode tasks = plays.get(0).get("tasks");
        // In free strategy, each host thread might have added the task.
        // But startTask is guarded by taskMap.computeIfAbsent(id, ...)
        // So there should be only 1 task entry for task1 because it's the same Task object
        assertEquals(1, tasks.size(), "Should have exactly one task entry in JSON for the same Task object");

        JsonNode hosts = tasks.get(0).get("hosts");
        assertTrue(hosts.has("host1"));
        assertTrue(hosts.has("host2"));
        assertEquals("pong", hosts.get("host1").get("ping").asText());
        assertEquals("pong", hosts.get("host2").get("ping").asText());
    }
}
