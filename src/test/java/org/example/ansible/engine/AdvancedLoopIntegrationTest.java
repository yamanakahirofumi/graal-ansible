package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedLoopIntegrationTest {

    @Test
    void testWithItemsFlattening() {
        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());

            String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  tasks:
                    - name: test with_items
                      debug:
                        msg: "{{ item }}"
                      with_items:
                        - apple
                        - [banana, cherry]
                      register: loop_result
                """;

            org.example.ansible.parser.YamlParser parser = new org.example.ansible.parser.YamlParser();
            Playbook playbook = parser.parse(new java.io.ByteArrayInputStream(playbookYaml.getBytes()));
            Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);
            List<TaskResult> hostResults = results.get("localhost");

            TaskResult taskResult = hostResults.get(0);
            List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) taskResult.data().get("results");

            // with_items should flatten one level, so 3 items: apple, banana, cherry
            assertEquals(3, iterationResults.size());
            assertEquals("apple", iterationResults.get(0).get("item"));
            assertEquals("banana", iterationResults.get(1).get("item"));
            assertEquals("cherry", iterationResults.get(2).get("item"));
        } finally {
            taskExecutor.close();
        }
    }

    @Test
    void testWithDict() {
        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());

            String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  vars:
                    my_dict:
                      a: 1
                      b: 2
                  tasks:
                    - name: test with_dict
                      debug:
                        msg: "{{ item.key }} = {{ item.value }}"
                      with_dict: "{{ my_dict }}"
                      register: loop_result
                """;

            org.example.ansible.parser.YamlParser parser = new org.example.ansible.parser.YamlParser();
            Playbook playbook = parser.parse(new java.io.ByteArrayInputStream(playbookYaml.getBytes()));
            Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);
            List<TaskResult> hostResults = results.get("localhost");

            TaskResult taskResult = hostResults.get(0);
            List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) taskResult.data().get("results");

            assertEquals(2, iterationResults.size());

            // Sort by key for reliable assertion
            iterationResults.sort((m1, m2) -> {
                Map<String, Object> item1 = (Map<String, Object>) m1.get("item");
                Map<String, Object> item2 = (Map<String, Object>) m2.get("item");
                return item1.get("key").toString().compareTo(item2.get("key").toString());
            });

            Map<String, Object> item0 = (Map<String, Object>) iterationResults.get(0).get("item");
            assertEquals("a", item0.get("key"));
            assertEquals(1, item0.get("value"));

            Map<String, Object> item1 = (Map<String, Object>) iterationResults.get(1).get("item");
            assertEquals("b", item1.get("key"));
            assertEquals(2, item1.get("value"));
        } finally {
            taskExecutor.close();
        }
    }
}
