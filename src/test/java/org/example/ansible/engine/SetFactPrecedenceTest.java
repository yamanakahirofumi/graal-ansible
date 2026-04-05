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

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetFactPrecedenceTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());

        // Register a mock set_fact module for testing
        taskExecutor.registerModule("set_fact", (args, become, context) -> {
            return TaskResult.success(Map.of("ansible_facts", args));
        });

        taskExecutor.registerModule("debug", (args, become, context) -> {
            Object msg = args.get("msg");
            return TaskResult.success(Map.of("msg", msg));
        });
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testSetFactOverridesPlayVars() {
        // Levels: Play Vars (12) < set_fact (19)

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test set_fact precedence
                  hosts: all
                  vars:
                    my_var: play_val
                  tasks:
                    - name: set fact
                      set_fact:
                        my_var: set_fact_val
                    - name: check var
                      debug:
                        msg: "{{ my_var }}"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // The second task's debug message should be "set_fact_val"
        // Currently, it will likely be "play_val" because set_fact is stored as Level 11 (Host facts)
        // and Level 12 (Play vars) overrides Level 11.
        assertEquals("set_fact_val", results.get("host1").get(1).data().get("msg"),
            "set_fact (Level 19) should override Play variables (Level 12)");
    }
}
