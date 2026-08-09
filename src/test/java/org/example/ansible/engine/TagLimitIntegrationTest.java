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
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

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
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

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

    @Test
    void testBlockLevelTagInheritance() {
        String yaml = """
                - name: Play with block tags
                  hosts: all
                  tasks:
                    - block:
                        - name: Task 1 in block
                          debug: msg="task1"
                        - name: Task 2 in block with tag
                          debug: msg="task2"
                          tags: [specific_task_tag]
                      rescue:
                        - name: Rescue task in block
                          debug: msg="rescue1"
                      always:
                        - name: Always task in block
                          debug: msg="always1"
                      tags: [block_tag]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Case 1: Run with 'block_tag'
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("block_tag"), List.of(), null);

        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        // We have 1 block containing 2 tasks in block, and 1 in always. Rescue is not executed.
        // Task 1 and Task 2 should execute successfully. Always task in block should execute successfully.
        assertFalse(hostResults.stream().anyMatch(TaskResult::isSkipped), "All executed tasks should not be skipped when running with block_tag");

        // Case 2: Run with 'specific_task_tag'
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("specific_task_tag"), List.of(), null);
        hostResults = results.get("localhost");
        assertNotNull(hostResults);

        // Task 1 should be skipped, but Task 2 should execute successfully.
        // Let's inspect each task result.
        assertEquals(3, hostResults.size());
        assertTrue(hostResults.get(0).isSkipped(), "Task 1 should be skipped");
        assertFalse(hostResults.get(1).isSkipped(), "Task 2 should not be skipped");
    }

    @Test
    void testSpecialTagsInheritance() {
        String yaml = """
                - name: Play with special tags
                  hosts: all
                  tasks:
                    - name: Task with always
                      debug: msg="always_task"
                      tags: [always]
                    - name: Task with never
                      debug: msg="never_task"
                      tags: [never]
                    - name: Task with regular tag
                      debug: msg="regular"
                      tags: [regular_tag]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Case 1: Run with 'regular_tag'.
        // Expected: always_task should run, regular_task should run, never_task should skip.
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("regular_tag"), List.of(), null);
        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        assertEquals(3, hostResults.size());
        assertFalse(hostResults.get(0).isSkipped(), "always task should run");
        assertTrue(hostResults.get(1).isSkipped(), "never task should be skipped");
        assertFalse(hostResults.get(2).isSkipped(), "regular task should run");

        // Case 2: Run with 'never'.
        // Expected: always_task should run, never_task should run, regular_task should be skipped.
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("never"), List.of(), null);
        hostResults = results.get("localhost");
        assertFalse(hostResults.get(0).isSkipped(), "always task should run");
        assertFalse(hostResults.get(1).isSkipped(), "never task should run");
        assertTrue(hostResults.get(2).isSkipped(), "regular task should be skipped");

        // Case 3: Skip 'always'.
        // Expected: always_task should be skipped.
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("all"), List.of("always"), null);
        hostResults = results.get("localhost");
        assertTrue(hostResults.get(0).isSkipped(), "always task should be skipped");
    }

    @Test
    void testComplexTagsMatching() {
        String yaml = """
                - name: Play with complex tags
                  hosts: all
                  tasks:
                    - name: Task 1
                      debug: msg="task1"
                      tags: [tag_a, tag_b]
                    - name: Task 2
                      debug: msg="task2"
                      tags: [tag_b, tag_c]
                    - name: Task 3
                      debug: msg="task3"
                      tags: [tag_c, tag_d]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Case 1: Run with 'tag_a' and 'tag_c', skip 'tag_d'.
        // Expected:
        // - Task 1 (tag_a, tag_b) should run because of tag_a.
        // - Task 2 (tag_b, tag_c) should run because of tag_c.
        // - Task 3 (tag_c, tag_d) should skip because it has tag_d which is skipped.
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("tag_a", "tag_c"), List.of("tag_d"), null);
        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        assertEquals(3, hostResults.size());
        assertFalse(hostResults.get(0).isSkipped(), "Task 1 should execute because of tag_a");
        assertFalse(hostResults.get(1).isSkipped(), "Task 2 should execute because of tag_c");
        assertTrue(hostResults.get(2).isSkipped(), "Task 3 should skip because of tag_d");

        // Case 2: Run with 'all', skip 'tag_b' and 'tag_c'.
        // Expected:
        // - Task 1 (tag_a, tag_b) should skip because of tag_b.
        // - Task 2 (tag_b, tag_c) should skip because of tag_b/tag_c.
        // - Task 3 (tag_c, tag_d) should skip because of tag_c.
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("all"), List.of("tag_b", "tag_c"), null);
        hostResults = results.get("localhost");
        assertTrue(hostResults.get(0).isSkipped(), "Task 1 should skip because of tag_b");
        assertTrue(hostResults.get(1).isSkipped(), "Task 2 should skip because of tag_b/tag_c");
        assertTrue(hostResults.get(2).isSkipped(), "Task 3 should skip because of tag_c");
    }

    @Test
    void testLimitFilteringEdgeCases() {
        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");

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
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // 1. Limit with spaces: "host1,  host2"
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "host1,  host2");
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertFalse(results.containsKey("host3"));

        // 2. Limit with spaces at start/end: "  host1,host2  "
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "  host1,host2  ");
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertFalse(results.containsKey("host3"));

        // 3. Limit with nonexistent hosts: "host1,nonexistent"
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "host1,nonexistent");
        assertTrue(results.containsKey("host1"));
        assertFalse(results.containsKey("host2"));
        assertFalse(results.containsKey("host3"));
        assertFalse(results.containsKey("nonexistent"));

        // 4. Limit with empty/blank values: "  "
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "  ");
        assertTrue(results.containsKey("host1"));
        assertTrue(results.containsKey("host2"));
        assertTrue(results.containsKey("host3"));
    }
}
