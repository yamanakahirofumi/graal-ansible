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
}
