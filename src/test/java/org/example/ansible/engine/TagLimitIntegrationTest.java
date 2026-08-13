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
        // block_tag is inherited by task inside block
        String yaml = """
                - name: Play
                  hosts: all
                  tasks:
                    - name: Outer task
                      debug: msg="outer"
                    - block:
                        - name: Inner task
                          debug: msg="inner"
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

        // Run with block_tag only
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("block_tag"), List.of(), null);

        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        assertEquals(2, hostResults.size());
        assertTrue(hostResults.get(0).isSkipped(), "outer task should be skipped");
        assertFalse(hostResults.get(1).isSkipped(), "inner task should be executed");
    }

    @Test
    void testSpecialTagPriorities() {
        String yaml = """
                - name: Play
                  hosts: all
                  tasks:
                    - name: task always
                      debug: msg="always"
                      tags: [always]
                    - name: task never
                      debug: msg="never"
                      tags: [never]
                    - name: task normal
                      debug: msg="normal"
                      tags: [normal]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Case 1: run with --tags "normal" and --skip-tags "always"
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("normal"), List.of("always"), null);
        List<TaskResult> hostResults = results.get("localhost");
        assertTrue(hostResults.get(0).isSkipped(), "always task should be skipped because 'always' is in skipTags");
        assertTrue(hostResults.get(1).isSkipped(), "never task should be skipped");
        assertFalse(hostResults.get(2).isSkipped(), "normal task should be executed");

        // Case 2: run with --tags "never"
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("never"), List.of(), null);
        hostResults = results.get("localhost");
        assertFalse(hostResults.get(0).isSkipped(), "always task should run");
        assertFalse(hostResults.get(1).isSkipped(), "never task should run when explicitly run");
        assertTrue(hostResults.get(2).isSkipped(), "normal task should be skipped");
    }

    @Test
    void testNestedStructuresAndTagMatching() {
        String yaml = """
                - name: Play
                  hosts: all
                  tasks:
                    - block:
                        - name: inside block tagged
                          debug: msg="match"
                          tags: [target]
                        - name: inside block untagged
                          debug: msg="nomatch"
                      rescue:
                        - name: inside rescue tagged
                          debug: msg="match rescue"
                          tags: [target]
                      always:
                        - name: inside always tagged
                          debug: msg="match always"
                          tags: [target]
                        - name: inside always untagged
                          debug: msg="nomatch always"
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), argThat(t -> t != null && "inside block tagged".equals(t.name())), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.failure("block task failed"));
        when(executor.execute(any(), any(), argThat(t -> t != null && !"inside block tagged".equals(t.name())), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Run with tags 'target'
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of("target"), List.of(), null);

        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);

        System.out.println("TEST_RESULTS: " + hostResults);

        assertEquals(4, hostResults.size());
        assertFalse(hostResults.get(0).success()); // failed
        assertFalse(hostResults.get(0).isSkipped());

        assertTrue(hostResults.get(1).success()); // rescue tagged executed
        assertFalse(hostResults.get(1).isSkipped());

        assertTrue(hostResults.get(2).success()); // always tagged executed
        assertFalse(hostResults.get(2).isSkipped());

        assertTrue(hostResults.get(3).isSkipped()); // untagged always skipped
    }

    @Test
    void testSpacingAndNonexistentHostLimit() {
        String yaml = """
                - name: Play
                  hosts: all
                  tasks:
                    - name: task1
                      debug: msg="task1"
                      tags: [tag1]
                    - name: task2
                      debug: msg="task2"
                      tags: [tag2]
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Inventory inventory = new Inventory(new Group("all", List.of(host1, host2), List.of(), Map.of()));
        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));

        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Test spacing trimming in tags: " tag1 , tag2 "
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(" tag1 , tag2 "), List.of(), null);
        assertNotNull(results.get("host1"));
        assertFalse(results.get("host1").get(0).isSkipped(), "task1 should run");
        assertFalse(results.get("host1").get(1).isSkipped(), "task2 should run");

        // Test nonexistent limit and spacing limit: " host1 , nonexistent "
        results.clear();
        tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), " host1 , nonexistent ");
        assertTrue(results.containsKey("host1"), "host1 should be executed");
        assertFalse(results.containsKey("host2"), "host2 should be limited out");

        // Test totally nonexistent host limit: "nonexistent"
        results.clear();
        assertDoesNotThrow(() -> {
            tqm.executePlay(playbook.plays().get(0), inventory, vm, results, false, List.of(), List.of(), "nonexistent");
        }, "Should safely skip play and not throw exception on nonexistent limit");
        assertTrue(results.isEmpty(), "No results should be recorded as play is skipped safely");
    }
}
