package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LoopControlIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testLoopControl() {
        String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  tasks:
                    - name: Test loop_control
                      debug:
                        msg: "item={{ my_item }}, index={{ my_idx }}"
                      loop:
                        - apple
                        - banana
                      loop_control:
                        loop_var: my_item
                        index_var: my_idx
                        label: "{{ my_item }} at {{ my_idx }}"
                      register: loop_result
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);
            List<TaskResult> hostResults = results.get("localhost");
            assertNotNull(hostResults);

            // Result 0 is 'Test loop_control'
            TaskResult taskResult = hostResults.get(0);
            Map<String, Object> loopResult = taskResult.data();
            assertNotNull(loopResult);

            List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) loopResult.get("results");
            assertEquals(2, iterationResults.size());

            // First item
            Map<String, Object> res0 = iterationResults.get(0);
            // In debug module, the item is available under 'item' key in result data by default if not overridden,
            // but loop_var changes the variable name in the execution context.
            // Our implementation of buildIterationResultData puts it under 'item' key ALWAYS for now.
            // Wait, I should check if I used loopVar for iterationResults data.
            assertEquals("apple", res0.get("item"));
            assertEquals("apple", res0.get("my_item"));
            assertEquals(0, res0.get("my_idx"));
            assertEquals("apple at 0", res0.get("_ansible_item_label"));

            // Second item
            Map<String, Object> res1 = iterationResults.get(1);
            assertEquals("banana", res1.get("item"));
            assertEquals("banana", res1.get("my_item"));
            assertEquals(1, res1.get("my_idx"));
            assertEquals("banana at 1", res1.get("_ansible_item_label"));
        } finally {
            taskExecutor.close();
        }
    }

    @Test
    void testLoopControlPause() {
        String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  tasks:
                    - name: Test loop_control pause
                      debug:
                        msg: "item={{ item }}"
                      loop:
                        - 1
                        - 2
                      loop_control:
                        pause: 1
                """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor);

            long start = System.currentTimeMillis();
            executor.execute(playbook, inventory);
            long end = System.currentTimeMillis();

            // Should take at least 1 second (1 second pause between 1 and 2)
            assertTrue((end - start) >= 1000, "Should have paused for at least 1 second, but took " + (end - start) + "ms");
        } finally {
            taskExecutor.close();
        }
    }
}
