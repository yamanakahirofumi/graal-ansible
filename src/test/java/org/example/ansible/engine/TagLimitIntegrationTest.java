package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TagLimitIntegrationTest {

    @Test
    void testTagFiltering() {
        String yaml = """
                - name: Play with tags
                  hosts: all
                  tags: [play_tag]
                  tasks:
                    - name: Task with no specific tags
                      debug: msg="task1"
                    - name: Task with specific tags
                      debug: msg="task2"
                      tags: [task_tag]
                    - name: Task with always tag
                      debug: msg="task3"
                      tags: [always]
                    - name: Task with never tag
                      debug: msg="task4"
                      tags: [never]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Case 1: Run with 'task_tag'
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("task_tag"), List.of(), null);

        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        assertEquals(4, hostResults.size());

        assertTrue(hostResults.get(0).isSkipped(), "task1 should be skipped");
        assertFalse(hostResults.get(1).isSkipped(), "task2 should be executed");
        assertFalse(hostResults.get(2).isSkipped(), "task3 should be executed (always)");
        assertTrue(hostResults.get(3).isSkipped(), "task4 should be skipped (never)");

        // Case 2: Run with 'never'
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("never"), List.of(), null);
        hostResults = results.get("localhost");
        assertTrue(hostResults.get(0).isSkipped());
        assertTrue(hostResults.get(1).isSkipped());
        assertFalse(hostResults.get(2).isSkipped()); // always
        assertFalse(hostResults.get(3).isSkipped()); // never

        // Case 3: Skip 'task_tag'
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("all"), List.of("task_tag"), null);
        hostResults = results.get("localhost");
        assertFalse(hostResults.get(0).isSkipped());
        assertTrue(hostResults.get(1).isSkipped());
        assertFalse(hostResults.get(2).isSkipped());
        assertTrue(hostResults.get(3).isSkipped());
    }

    @Test
    void testLimitFilteringWithNestedGroups() {
        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");

        // Structure:
        // all
        //  - web (host1)
        //  - db (host2, host3)
        Group web = new Group("web", List.of(host1), List.of(), Map.of());
        Group db = new Group("db", List.of(host2, host3), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, db), Map.of());
        Inventory inventory = new Inventory(all);

        String yaml = """
                - name: Play for all
                  hosts: all
                  tasks:
                    - name: ping
                      ping:
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // 1. Limit to host1 (nested in web)
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "host1");
        assertTrue(results.containsKey("host1"));
        assertFalse(results.containsKey("host2"));
        assertFalse(results.containsKey("host3"));

        // 2. Limit to group db (nested in all)
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "db");
        assertFalse(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertTrue(results.containsKey("host3"));

        // 3. Comma-separated list
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "host1,host2");
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertFalse(results.containsKey("host3"));
    }
}
