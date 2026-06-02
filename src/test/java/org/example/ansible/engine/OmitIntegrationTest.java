package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OmitIntegrationTest {

    @Test
    void testOmitVariable() {
        // We use a mock module that captures its arguments
        TaskExecutor taskExecutor = new TaskExecutor();
        try {
            taskExecutor.registerModule("test_module", (args, become, context) -> {
                return TaskResult.success(false, Map.copyOf(args));
            });

            PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());

            String playbookYaml = """
                - hosts: localhost
                  gather_facts: no
                  vars:
                    my_var: "present"
                    missing_var: "{{ omit }}"
                  tasks:
                    - name: test present
                      test_module:
                        arg1: "{{ my_var }}"
                        arg2: "static"
                      register: res1
                    - name: test omitted
                      test_module:
                        arg1: "{{ missing_var }}"
                        arg2: "static"
                      register: res2
                """;

            org.example.ansible.parser.YamlParser parser = new org.example.ansible.parser.YamlParser();
            Playbook playbook = parser.parse(new java.io.ByteArrayInputStream(playbookYaml.getBytes()));
            Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));

            Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);
            List<TaskResult> hostResults = results.get("localhost");

            // Task 1: Both args should be present
            Map<String, Object> data1 = hostResults.get(0).data();
            assertEquals("present", data1.get("arg1"));
            assertEquals("static", data1.get("arg2"));

            // Task 2: arg1 should be omitted
            Map<String, Object> data2 = hostResults.get(1).data();
            assertFalse(data2.containsKey("arg1"), "arg1 should have been omitted");
            assertEquals("static", data2.get("arg2"));
        } finally {
            taskExecutor.close();
        }
    }
}
