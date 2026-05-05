package org.example.ansible.engine;

import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VarsPromptIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

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
    void testVarsPromptPrecedence() {
        // Level 12 (Play vars) < Level 13 (vars_prompt) < Level 14 (vars_files) < Level 22 (extra vars)

        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  vars:
                    my_var: from_play_vars
                  vars_prompt:
                    - name: my_var
                      prompt: "Enter my_var"
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ my_var }}"
                """;

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Mock PromptProvider
        playbookExecutor.setPromptProvider(promptDef -> "from_prompt");

        // 1. Level 13 (prompt) should override Level 12 (play vars)
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);
        assertEquals("from_prompt", results.get("host1").get(0).data().get("msg"));

        // 2. Level 22 (extra vars) should override Level 13 (prompt) AND skip prompting
        Map<String, Object> extraVars = Map.of("my_var", "from_extra_vars");
        // We use a counter to verify prompt wasn't called
        int[] promptCount = {0};
        playbookExecutor.setPromptProvider(promptDef -> {
            promptCount[0]++;
            return "should_be_skipped";
        });

        results = playbookExecutor.execute(playbook, inventory, extraVars);
        assertEquals("from_extra_vars", results.get("host1").get(0).data().get("msg"));
        assertEquals(0, promptCount[0], "Prompt should have been skipped");
    }

    @Test
    void testMultipleVarsPrompt() {
        String inventoryIni = "host1";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: play
                  hosts: all
                  vars_prompt:
                    - name: var1
                    - name: var2
                  tasks:
                    - name: task
                      debug:
                        msg: "{{ var1 }} and {{ var2 }}"
                """;

        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> answers = Map.of("var1", "val1", "var2", "val2");
        playbookExecutor.setPromptProvider(promptDef -> answers.get(promptDef.get("name")));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);
        assertEquals("val1 and val2", results.get("host1").get(0).data().get("msg"));
    }
}
