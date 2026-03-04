package org.example.ansible.engine;

import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskControlTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor);
    }

    @Test
    void testWhenConditionList() {
        // Arrange
        String inventoryIni = "host1 run_a=true run_b=true\nhost2 run_a=true run_b=false";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test when list
                  hosts: all
                  tasks:
                    - name: conditional task
                      debug:
                        msg: "hello"
                      when:
                        - run_a
                        - run_b
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        assertTrue(results.get("host1").get(0).success());
        assertNull(results.get("host1").get(0).data().get("skipped"));

        assertTrue(Boolean.TRUE.equals(results.get("host2").get(0).data().get("skipped")));
    }

    @Test
    void testEmptyStringTruthiness() {
        // Arrange
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test truthiness
                  hosts: all
                  vars:
                    empty_var: ""
                    non_empty_var: "hi"
                  tasks:
                    - name: skip me
                      debug:
                        msg: "skip"
                      when: empty_var
                    - name: run me
                      debug:
                        msg: "run"
                      when: non_empty_var
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        assertTrue(isSkipped(results.get("host1").get(0)));
        assertFalse(isSkipped(results.get("host1").get(1)));
    }

    private boolean isSkipped(TaskResult result) {
        return Boolean.TRUE.equals(result.data().get("skipped"));
    }

    @Test
    void testWhenCondition() {
        // Arrange
        String inventoryIni = "host1 run_task=true\nhost2 run_task=false";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test when
                  hosts: all
                  tasks:
                    - name: conditional task
                      debug:
                        msg: "hello"
                      when: run_task
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        // host1 should have 1 success result
        assertEquals(1, results.get("host1").size());
        assertTrue(results.get("host1").get(0).success());
        assertNull(results.get("host1").get(0).data().get("skipped"));

        // host2 should have 1 skipped result
        assertEquals(1, results.get("host2").size());
        assertTrue(Boolean.TRUE.equals(results.get("host2").get(0).data().get("skipped")));
    }

    @Test
    void testLoop() {
        // Arrange
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test loop
                  hosts: all
                  tasks:
                    - name: loop task
                      debug:
                        msg: "item is {{ item }}"
                      loop:
                        - one
                        - two
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        List<TaskResult> host1Results = results.get("host1");
        assertEquals(1, host1Results.size());
        TaskResult loopResult = host1Results.get(0);
        assertTrue(loopResult.success());

        List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) loopResult.data().get("results");
        assertEquals(2, iterationResults.size());
        assertEquals("item is one", iterationResults.get(0).get("msg"));
        assertEquals("item is two", iterationResults.get(1).get("msg"));
    }

    @Test
    void testRegister() {
        // Arrange
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test register
                  hosts: all
                  tasks:
                    - name: task 1
                      debug:
                        msg: "captured"
                      register: task1_result
                    - name: task 2
                      debug:
                        msg: "previous was {{ task1_result.msg }}"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        List<TaskResult> host1Results = results.get("host1");
        assertEquals(2, host1Results.size());
        assertEquals("previous was captured", host1Results.get(1).data().get("msg"));
    }

    @Test
    void testFailedWhen() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test failed_when
                  hosts: all
                  tasks:
                    - name: fail me
                      debug:
                        msg: "not really failed"
                      failed_when: true
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertFalse(results.get("host1").get(0).success());
    }

    @Test
    void testChangedWhen() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test changed_when
                  hosts: all
                  tasks:
                    - name: change me
                      debug:
                        msg: "forced change"
                      changed_when: true
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertTrue(results.get("host1").get(0).changed());
    }

    @Test
    void testIgnoreErrors() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test ignore_errors
                  hosts: all
                  tasks:
                    - name: fail and ignore
                      debug:
                        msg: "fail"
                      failed_when: true
                      ignore_errors: true
                    - name: should run
                      debug:
                        msg: "ran"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertEquals(2, results.get("host1").size());
        assertFalse(results.get("host1").get(0).success());
        assertTrue(results.get("host1").get(1).success());
    }

    @Test
    void testHandlers() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test handlers
                  hosts: all
                  tasks:
                    - name: trigger handler
                      debug:
                        msg: "trigger"
                      changed_when: true
                      notify: my handler
                  handlers:
                    - name: my handler
                      debug:
                        msg: "handled"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertEquals(2, results.get("host1").size());
        assertEquals("handled", results.get("host1").get(1).data().get("msg"));
    }

    @Test
    void testWithItems() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test with_items
                  hosts: all
                  tasks:
                    - name: loop task
                      debug:
                        msg: "item is {{ item }}"
                      with_items:
                        - one
                        - two
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        List<TaskResult> host1Results = results.get("host1");
        TaskResult loopResult = host1Results.get(0);
        List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) loopResult.data().get("results");
        assertEquals(2, iterationResults.size());
    }

    @Test
    void testFailedWhenList() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test failed_when list
                  hosts: all
                  tasks:
                    - name: fail me if any is true
                      debug:
                        msg: "fail"
                      failed_when:
                        - false
                        - true
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertFalse(results.get("host1").get(0).success());
    }

    @Test
    void testChangedWhenList() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test changed_when list
                  hosts: all
                  tasks:
                    - name: change me if all are true
                      debug:
                        msg: "change"
                      changed_when:
                        - true
                        - true
                    - name: don't change if any is false
                      debug:
                        msg: "no change"
                      changed_when:
                        - true
                        - false
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        taskExecutor.registerModule("debug", args -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        assertTrue(results.get("host1").get(0).changed());
        assertFalse(results.get("host1").get(1).changed());
    }
}
