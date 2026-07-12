package org.example.ansible.engine;

import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.parser.YamlParser;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrePostTasksIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    public void testPrePostTasksOrderAndHandlers() {
        String playbookYaml = """
                - name: Test Pre and Post Tasks
                  hosts: localhost
                  pre_tasks:
                    - name: pre task 1
                      debug:
                        msg: "pre task 1"
                      notify: pre handler
                  tasks:
                    - name: main task 1
                      debug:
                        msg: "main task 1"
                      notify: main handler
                  post_tasks:
                    - name: post task 1
                      debug:
                        msg: "post task 1"
                      notify: post handler
                  handlers:
                    - name: pre handler
                      set_fact:
                        pre_handler_executed: true
                    - name: main handler
                      set_fact:
                        main_handler_executed: true
                    - name: post handler
                      set_fact:
                        post_handler_executed: true
                """;

        // Mock TaskExecutor to always return changed=true to trigger handlers
        ITaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor pe = new PlaybookExecutor(taskExecutor);
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Inventory inventory = new Inventory();
        inventory.addHostToGroup("localhost", "all");

        Map<String, List<TaskResult>> results = pe.execute(playbook, inventory);

        List<TaskResult> hostResults = results.get("localhost");

        // Order should be:
        // 1. pre task 1
        // 2. pre handler
        // 3. main task 1
        // 4. main handler
        // 5. post task 1
        // 6. post handler

        assertEquals(6, hostResults.size(), "Should have 6 results (3 tasks + 3 handlers)");
        assertEquals("pre task 1", hostResults.get(0).data().get("msg"));
        assertTrue(hostResults.get(1).data().containsKey("ansible_facts"), "Second result should be pre handler");
        assertTrue(((Map)hostResults.get(1).data().get("ansible_facts")).containsKey("pre_handler_executed"));

        assertEquals("main task 1", hostResults.get(2).data().get("msg"));
        assertTrue(hostResults.get(3).data().containsKey("ansible_facts"), "Fourth result should be main handler");
        assertTrue(((Map)hostResults.get(3).data().get("ansible_facts")).containsKey("main_handler_executed"));

        assertEquals("post task 1", hostResults.get(4).data().get("msg"));
        assertTrue(hostResults.get(5).data().containsKey("ansible_facts"), "Sixth result should be post handler");
        assertTrue(((Map)hostResults.get(5).data().get("ansible_facts")).containsKey("post_handler_executed"));
    }

    private static class MockTaskExecutor implements ITaskExecutor {
        @Override
        public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, Connection connection, ConnectionFactory connectionFactory) {
            return execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, null, null, connection, connectionFactory);
        }

        @Override
        public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
            Map<String, Object> data = new java.util.HashMap<>(task.args());
            if ("set_fact".equals(task.action())) {
                data.put("ansible_facts", task.args());
            }
            // Always return changed=true to trigger handlers
            return new TaskResult(true, true, "OK", data);
        }

        @Override public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) { return null; }
        @Override public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) { return null; }
        @Override public void setCollectionPaths(List<String> collectionPaths) {}
        @Override public List<String> getCollectionPaths() { return List.of(); }
        @Override public org.example.ansible.util.OSHandler getOsHandler() { return null; }
        @Override public VariableResolver getVariableResolver() { return new VariableResolver(); }
        @Override public VariableManager getVariableManager() { return null; }
        @Override public String resolveLocalPath(String path) { return path; }
        @Override public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) { return Map.of(); }
        @Override public void close() {}
    }
}
