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

import static org.junit.jupiter.api.Assertions.*;

class IncludeVarsIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());
        // register debug module to capture messages
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
    void testIncludeVarsFromFile() throws IOException {
        // Prepare external vars file
        Path varsFile = tempDir.resolve("external_vars.yml");
        Files.writeString(varsFile, "external_var: hello_from_file\noverridden_var: from_file");

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = String.format("""
                - name: play
                  hosts: all
                  tasks:
                    - name: include external vars
                      include_vars:
                        file: %s
                    - name: use external var
                      debug:
                        msg: "{{ external_var }}"
                """, varsFile.toAbsolutePath().toString().replace("\\", "/"));

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, tempDir, false);

        // Assert
        assertEquals("hello_from_file", results.get("host1").get(1).data().get("msg"));
    }

    @Test
    void testIncludeVarsPrecedence() throws IOException {
        // Level 17 (Task Vars) < Level 18 (include_vars) < Level 19 (Registered Vars)

        Path varsFile = tempDir.resolve("prec_vars.yml");
        Files.writeString(varsFile, "my_var: level_18");

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = String.format("""
                - name: play
                  hosts: all
                  tasks:
                    - name: include vars
                      include_vars: %s
                    - name: test level 18 wins over level 17
                      vars:
                        my_var: level_17
                      debug:
                        msg: "{{ my_var }}"
                    - name: register level 19
                      shell: echo level_19
                      register: reg_result
                    - name: test level 19 wins over level 18
                      vars:
                        reg_result:
                          stdout: level_18_disguised_as_task_var
                      debug:
                        msg: "{{ reg_result.stdout }}"
                """, varsFile.toAbsolutePath().toString().replace("\\", "/"));

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, tempDir, false);

        // Assert
        // Task 1: include_vars (index 0)
        // Task 2: debug (index 1) -> should be level_18
        assertEquals("level_18", results.get("host1").get(1).data().get("msg"));
        // Task 4: debug (index 3) -> should be level_19
        assertEquals("level_19", results.get("host1").get(3).data().get("msg").toString().trim());
    }

    @Test
    void testIncludeVarsFromDirectory() throws IOException {
        Path varsDir = tempDir.resolve("vars_dir");
        Files.createDirectories(varsDir);
        Files.writeString(varsDir.resolve("a.yml"), "var_a: value_a");
        Files.writeString(varsDir.resolve("b.yml"), "var_b: value_b");

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = String.format("""
                - name: play
                  hosts: all
                  tasks:
                    - name: include vars from dir
                      include_vars:
                        dir: %s
                    - name: check vars
                      debug:
                        msg: "{{ var_a }} and {{ var_b }}"
                """, varsDir.toAbsolutePath().toString().replace("\\", "/"));

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, tempDir, false);

        // Assert
        assertEquals("value_a and value_b", results.get("host1").get(1).data().get("msg"));
    }
}
