package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional integration tests for dynamic variable resolution, complex Jinja2 filter pipelines,
 * and multi-level data structure transformations as specified in Test-Expansion-Strategy.md (Section 2.4).
 */
class DynamicVariableResolutionTest {

    private TaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private VariableManager vm;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();

        // Register debug module
        taskExecutor.registerModule("debug", (args, become, context) -> {
            Map<String, Object> data = new HashMap<>(args);
            if (!data.containsKey("msg")) {
                data.put("msg", "ok");
            }
            return TaskResult.success(false, data);
        });

        // Register set_fact module emulation
        taskExecutor.registerModule("set_fact", (args, become, context) -> {
            Map<String, Object> facts = new HashMap<>();
            if (args.containsKey("key_value_params")) {
                facts.putAll((Map<String, Object>) args.get("key_value_params"));
            }
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                if (!entry.getKey().equals("key_value_params")) {
                    facts.put(entry.getKey(), entry.getValue());
                }
            }
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("ansible_facts", facts);
            return TaskResult.success(true, resultData);
        });

        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());
        Host host = new Host("localhost");
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        vm = new VariableManager(inventory, Map.of("base_env", "production"), tempDir);
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testComplexJinja2FilterPipelineResolution() {
        // Variables containing dicts and lists for transformation tests
        Map<String, Object> vars = Map.of(
            "dict1", Map.of("a", 1, "b", 2),
            "dict2", Map.of("b", 20, "c", 30),
            "list1", List.of("apple", "banana", "cherry"),
            "list2", List.of("banana", "dragonfruit")
        );

        Task combineTask = new Task(
            "test combine and dict2items filter",
            "debug",
            Map.of("merged", "{{ dict1 | combine(dict2) }}", "items", "{{ dict1 | dict2items }}")
        );

        Task listFilterTask = new Task(
            "test list set filters",
            "debug",
            Map.of("diff", "{{ list1 | difference(list2) }}", "union_res", "{{ list1 | union(list2) }}")
        );

        Play play = new Play("Jinja2 Filter Test Play", "all", List.of(combineTask, listFilterTask), vars);
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());

        // Assert combine filter result
        Map<String, Object> mergedDict = (Map<String, Object>) hostResults.get(0).data().get("merged");
        assertNotNull(mergedDict);
        assertEquals(1, ((Number) mergedDict.get("a")).intValue());
        assertEquals(20, ((Number) mergedDict.get("b")).intValue());
        assertEquals(30, ((Number) mergedDict.get("c")).intValue());

        // Assert dict2items filter result
        List<Map<String, Object>> itemsList = (List<Map<String, Object>>) hostResults.get(0).data().get("items");
        assertNotNull(itemsList);
        assertEquals(2, itemsList.size());

        // Assert set filter results
        List<Object> diffList = (List<Object>) hostResults.get(1).data().get("diff");
        assertNotNull(diffList);
        assertTrue(diffList.contains("apple"));
        assertTrue(diffList.contains("cherry"));
        assertFalse(diffList.contains("banana"));

        List<Object> unionList = (List<Object>) hostResults.get(1).data().get("union_res");
        assertNotNull(unionList);
        assertEquals(4, unionList.size());
        assertTrue(unionList.containsAll(List.of("apple", "banana", "cherry", "dragonfruit")));
    }

    @Test
    void testRecursiveNestedVariableEvaluationAcrossTasks() {
        // Task 1: Dynamically set nested facts via set_fact
        Task setFactTask = new Task(
            "set dynamic facts",
            "set_fact",
            Map.of("app_config", Map.of("port", 8080, "host", "127.0.0.1", "env", "{{ base_env }}"))
        );

        // Task 2: Access dynamically set facts in Jinja2 template strings
        Task readFactTask = new Task(
            "read dynamic facts",
            "debug",
            Map.of("connection_str", "http://{{ app_config.host }}:{{ app_config.port }}/{{ app_config.env }}")
        );

        Play play = new Play("Dynamic Fact Resolution Play", "all", List.of(setFactTask, readFactTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());

        assertTrue(hostResults.get(0).success());
        assertTrue(hostResults.get(0).changed());

        TaskResult readResult = hostResults.get(1);
        assertTrue(readResult.success());
        assertEquals("http://127.0.0.1:8080/production", readResult.data().get("connection_str"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictFilterWithDynamicLoop() {
        Map<String, Object> vars = Map.of(
            "kv_pairs", List.of(
                Map.of("key", "db_name", "value", "mydb"),
                Map.of("key", "db_port", "value", 5432)
            )
        );

        Task transformTask = new Task(
            "transform items to dict",
            "debug",
            Map.of("db_config", "{{ kv_pairs | items2dict }}")
        );

        Play play = new Play("Items2Dict Play", "all", List.of(transformTask), vars);
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());

        Map<String, Object> dbConfig = (Map<String, Object>) hostResults.get(0).data().get("db_config");
        assertNotNull(dbConfig);
        assertEquals("mydb", dbConfig.get("db_name"));
        assertEquals(5432, ((Number) dbConfig.get("db_port")).intValue());
    }
}
