package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.example.ansible.util.OSHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BecomeTest {

    private static class MockTaskExecutor implements ITaskExecutor {
        private final OSHandler osHandler = mock(OSHandler.class);
        public final List<Task> executedTasks = new java.util.ArrayList<>();
        public final List<BecomeContext> executedContexts = new java.util.ArrayList<>();

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext) {
            executedTasks.add(task);
            executedContexts.add(becomeContext);
            return TaskResult.success(Map.of());
        }

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext, org.example.ansible.connection.Connection connection) {
            return execute(task, becomeContext);
        }

        @Override
        public OSHandler getOsHandler() {
            return osHandler;
        }

        @Override
        public org.example.ansible.module.Module getModule(String action) {
            return (args, becomeContext, context) -> TaskResult.success(Map.of());
        }

        @Override
        public void close() {}
    }

    @Test
    void testBecomeResolution() {
        String yaml = """
            - name: Play with become
              hosts: all
              become: yes
              become_user: admin
              tasks:
                - name: Task without override
                  debug:
                    msg: hello
                - name: Task with override
                  debug:
                    msg: world
                  become: no
                - name: Task with user override
                  debug:
                    msg: override
                  become_user: root
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        playbookExecutor.execute(playbook, inventory);

        List<BecomeContext> contexts = taskExecutor.executedContexts;
        assertEquals(3, contexts.size());

        // Task 1: inherits from play
        assertTrue(contexts.get(0).become());
        assertEquals("admin", contexts.get(0).becomeUser());

        // Task 2: overrides become: no
        assertFalse(contexts.get(1).become());

        // Task 3: overrides become_user: root
        assertTrue(contexts.get(2).become());
        assertEquals("root", contexts.get(2).becomeUser());
    }

    @Test
    void testBecomeVariableResolution() {
        String yaml = """
            - name: Play with variables
              hosts: all
              become: "{{ use_become }}"
              become_user: "{{ target_user }}"
              vars:
                use_become: yes
                target_user: deploy
              tasks:
                - name: Resolved task
                  debug:
                    msg: hello
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        playbookExecutor.execute(playbook, inventory);

        assertEquals(1, taskExecutor.executedContexts.size());
        BecomeContext context = taskExecutor.executedContexts.get(0);
        assertTrue(context.become());
        assertEquals("deploy", context.becomeUser());
    }
}
