package org.example.ansible.engine;

import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.io.FileInputStream;

import static org.junit.jupiter.api.Assertions.*;

class VariablePrecedenceIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());
        taskExecutor.registerModule("debug", (args, become, context) -> {
            Object msg = args.get("msg");
            return TaskResult.success(false, Map.of("msg", msg));
        });
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testDirectoryVarsPrecedence() throws IOException {
        // Setup inventory directory
        Path invDir = tempDir.resolve("inventory");
        Files.createDirectories(invDir.resolve("group_vars"));
        Files.writeString(invDir.resolve("group_vars/all.yml"), "dir_var: from_inventory_dir");

        Path invFile = invDir.resolve("hosts.ini");
        Files.writeString(invFile, "host1");

        // Setup playbook directory
        Path pbDir = tempDir.resolve("playbook");
        Files.createDirectories(pbDir.resolve("group_vars"));
        Files.writeString(pbDir.resolve("group_vars/all.yml"), "dir_var: from_playbook_dir\nother_var: pb_only");

        String playbookYaml = """
                - name: play
                  hosts: all
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ dir_var }} and {{ other_var }}"
                """;

        Inventory inventory = new IniInventoryParser().parse(new FileInputStream(invFile.toFile()));
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        // Playbook dir variables (Level 5/7) should override Inventory dir variables (Level 4/6)
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), pbDir, invDir, false);

        // Assert
        assertEquals("from_playbook_dir and pb_only", results.get("host1").get(0).data().get("msg"));
    }

    @Test
    void testBlockVarsPropagation() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  tasks:
                    - name: outer block
                      block:
                        - name: inner block
                          vars:
                            block_var: inner
                          block:
                            - name: task
                              debug:
                                msg: "{{ block_var }}"
                      vars:
                        block_var: outer
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of());

        // Assert
        assertEquals("inner", results.get("host1").get(0).data().get("msg"));
    }

    @Test
    void testFactAndRegisteredVarsPrecedence() {
        // Levels: Facts (11) < Task Vars (17) < Registered Vars (19)

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  tasks:
                    - name: set fact
                      set_fact:
                        my_var: fact_val
                    - name: task with vars
                      vars:
                        my_var: task_val
                      debug:
                        msg: "{{ my_var }}"
                    - name: register var
                      shell: echo reg_val
                      register: reg_result
                    - name: task after register
                      vars:
                        reg_result:
                          stdout: task_val
                      debug:
                        msg: "{{ reg_result.stdout }}"
                """;

        VariableManager vm = new VariableManager(inventory, Map.of());
        vm.addFacts("host1", Map.of("my_var", "fact_val"));

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Scenario 1: Fact (11)
        // Note: Task Var (17) should override Fact (11).
        // If 'my_var' was set via set_fact (19), it would override Task Var (17).
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, vm, false);
        // Task 1: set_fact (executed during play)
        // Task 2: check var with task-level vars: my_var=task_val
        // In this test, vm.addFacts was called BEFORE execution.
        // During execution, Task 1 "set fact" runs and sets my_var: fact_val at Level 19.
        // Level 19 (set_fact) > Level 17 (task vars)
        assertEquals("fact_val", results.get("host1").get(1).data().get("msg"));

        // Scenario 2: Registered Var (19) > Task Var (17)
        // We'll simulate this by adding a registered variable manually to the VM
        vm.registerVariable("host1", "reg_result", Map.of("stdout", "reg_val"));
        results = playbookExecutor.execute(playbook, inventory, vm, false);
        // The 4th task has a task-level var "reg_result" with "stdout: task_val"
        // But the registered var "reg_result" (reg_val) should win.
        assertEquals("reg_val", results.get("host1").get(3).data().get("msg").toString().trim());
    }

    @Test
    void testAnsibleCheckModeFromCli() throws IOException {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  tasks:
                    - name: check ansible_check_mode
                      debug:
                        msg: "{{ ansible_check_mode }}"
                """;

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act with check mode = true
        Map<String, List<TaskResult>> resultsTrue = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, tempDir, true);
        assertEquals(true, resultsTrue.get("host1").get(0).data().get("msg"));

        // Act with check mode = false
        Map<String, List<TaskResult>> resultsFalse = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, tempDir, false);
        assertEquals(false, resultsFalse.get("host1").get(0).data().get("msg"));
    }
}
