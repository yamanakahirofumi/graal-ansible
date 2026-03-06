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

class VariablePriorityTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor);
        taskExecutor.registerModule("debug", (args, become, pythonContext) -> {
            Object msg = args.get("msg");
            return TaskResult.success(false, Map.of("msg", msg));
        });
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testVariablePriority() {
        // 1. Inventory variable
        String inventoryIni = "host1 my_var=inventory";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        // 2. Play variable
        // 3. Task variable
        String playbookYaml = """
                - name: play
                  hosts: all
                  vars:
                    my_var: play
                  tasks:
                    - name: task
                      vars:
                        my_var: task
                      debug:
                        msg: "{{ my_var }}"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // 4. Extra variable
        Map<String, Object> extraVars = Map.of("my_var", "extra");

        // Act & Assert (Extra > Task > Play > Inventory)

        // Test Extra
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, extraVars);
        assertEquals("extra", results.get("host1").get(0).data().get("msg"));

        // Test Task (remove extra)
        results = playbookExecutor.execute(playbook, inventory, Map.of());
        assertEquals("task", results.get("host1").get(0).data().get("msg"));

        // Test Play (remove task var from YAML)
        String playbookYamlNoTaskVar = """
                - name: play
                  hosts: all
                  vars:
                    my_var: play
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ my_var }}"
                """;
        playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYamlNoTaskVar.getBytes(StandardCharsets.UTF_8)));
        results = playbookExecutor.execute(playbook, inventory, Map.of());
        assertEquals("play", results.get("host1").get(0).data().get("msg"));

        // Test Inventory (remove play var from YAML)
        String playbookYamlNoPlayVar = """
                - name: play
                  hosts: all
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ my_var }}"
                """;
        playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYamlNoPlayVar.getBytes(StandardCharsets.UTF_8)));
        results = playbookExecutor.execute(playbook, inventory, Map.of());
        assertEquals("inventory", results.get("host1").get(0).data().get("msg"));
    }

    @Test
    void testVarsFilesPriority() throws IOException {
        // Prepare vars_file
        Path varsFile = tempDir.resolve("external_vars.yml");
        Files.writeString(varsFile, "external_var: value_from_file\nmy_var: from_file");

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  vars_files:
                    - external_vars.yml
                  vars:
                    my_var: play
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ my_var }} and {{ external_var }}"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir);

        // Assert
        // Priority 7 (vars_files) > Priority 6 (vars)
        assertEquals("from_file and value_from_file", results.get("host1").get(0).data().get("msg"));
    }
}
