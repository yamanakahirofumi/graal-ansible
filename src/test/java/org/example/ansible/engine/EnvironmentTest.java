package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentTest {

    private static class MockTaskExecutor extends TaskExecutor {
        public final List<Map<String, String>> capturedEnvironments = new java.util.ArrayList<>();

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) {
            capturedEnvironments.add(environment);
            return TaskResult.success(Map.of());
        }

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
            return execute(task, becomeContext, environment);
        }
    }

    @Test
    void testEnvironmentMergingAndResolution() {
        String yaml = """
            - name: Play with environment
              hosts: all
              environment:
                PLAY_VAR: "play_value"
                OVER_VAR: "play_override"
                TEMPLATE_VAR: "{{ dynamic_var }}"
              vars:
                dynamic_var: "dynamic_value"
                task_dynamic: "task_value"
              tasks:
                - name: Task with environment
                  debug:
                    msg: hello
                  environment:
                    TASK_VAR: "task_value"
                    OVER_VAR: "task_override"
                    TASK_TEMPLATE: "{{ task_dynamic }}"
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        playbookExecutor.execute(playbook, inventory);

        assertEquals(1, taskExecutor.capturedEnvironments.size());
        Map<String, String> env = taskExecutor.capturedEnvironments.get(0);

        assertEquals("play_value", env.get("PLAY_VAR"));
        assertEquals("task_override", env.get("OVER_VAR")); // Task overrides Play
        assertEquals("task_value", env.get("TASK_VAR"));
        assertEquals("dynamic_value", env.get("TEMPLATE_VAR")); // Play level template resolution
        assertEquals("task_value", env.get("TASK_TEMPLATE")); // Task level template resolution
    }

    @Test
    void testBlockEnvironmentMerging() {
        String yaml = """
            - name: Play environment
              hosts: all
              environment:
                PLAY_VAR: "play"
              tasks:
                - name: Block with environment
                  block:
                    - name: Inside block
                      debug:
                        msg: hello
                      environment:
                        TASK_VAR: "task"
                  environment:
                    BLOCK_VAR: "block"
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        playbookExecutor.execute(playbook, inventory);

        assertEquals(1, taskExecutor.capturedEnvironments.size());
        Map<String, String> env = taskExecutor.capturedEnvironments.get(0);

        assertEquals("play", env.get("PLAY_VAR"));
        assertEquals("block", env.get("BLOCK_VAR"));
        assertEquals("task", env.get("TASK_VAR"));
    }
}
