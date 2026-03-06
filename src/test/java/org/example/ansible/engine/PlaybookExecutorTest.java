package org.example.ansible.engine;

import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookExecutorTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testPlaybookWithVariableResolution() {
        // Arrange
        String inventoryIni = """
                [web]
                web1 http_port=8080
                web2 http_port=8081
                """;
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: deploy web
                  hosts: web
                  tasks:
                    - name: check port
                      debug:
                        msg: "listening on {{ http_port }}"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", (args, become, pythonContext) -> {
            String msg = (String) args.get("msg");
            return TaskResult.success(false, Map.of("msg", msg));
        });

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        assertEquals(2, results.size());
        assertTrue(results.containsKey("web1"));
        assertTrue(results.containsKey("web2"));

        assertEquals("listening on 8080", results.get("web1").get(0).data().get("msg"));
        assertEquals("listening on 8081", results.get("web2").get(0).data().get("msg"));
    }

    @Test
    void testHostFailureSkipsSubsequentTasks() {
        // Arrange
        String inventoryIni = """
                host1
                host2
                """;
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test failure
                  hosts: all
                  tasks:
                    - name: failing task
                      fail_on_host1: "yes"
                    - name: subsequent task
                      debug:
                        msg: "should not run on host1"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("fail_on_host1", (args, become, pythonContext) -> {
            return TaskResult.failure("failed");
        });

        String inventoryIniWithVar = """
                host1 fail_me=true
                host2 fail_me=false
                """;
        inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIniWithVar.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("fail_if_var_true", (args, become, pythonContext) -> {
            if ("true".equals(args.get("should_fail"))) {
                return TaskResult.failure("Intentional failure");
            }
            return TaskResult.success(false, Map.of());
        });
        taskExecutor.registerModule("debug", (args, become, pythonContext) -> TaskResult.success(false, Map.of()));

        String playbookYaml2 = """
                - name: test failure
                  hosts: all
                  tasks:
                    - name: maybe fail
                      fail_if_var_true:
                        should_fail: "{{ fail_me }}"
                    - name: subsequent
                      debug:
                        msg: "hi"
                """;
        playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml2.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        assertEquals(1, results.get("host1").size());
        assertFalse(results.get("host1").get(0).success());

        assertEquals(2, results.get("host2").size());
        assertTrue(results.get("host2").get(0).success());
        assertTrue(results.get("host2").get(1).success());
    }

    @Test
    void testTargetingGroup() {
        // Arrange
        String inventoryIni = """
                [db]
                db1
                [web]
                web1
                """;
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: db play
                  hosts: db
                  tasks:
                    - name: db task
                      debug:
                        msg: "db task"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        taskExecutor.registerModule("debug", (args, become, pythonContext) -> TaskResult.success(false, Map.of()));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        assertEquals(1, results.size());
        assertTrue(results.containsKey("db1"));
        assertFalse(results.containsKey("web1"));
    }
}
