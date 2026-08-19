package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.parser.YamlParser;
import org.example.ansible.util.OSHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BecomeTest {

    private static class MockTaskExecutor implements ITaskExecutor {
        private final OSHandler osHandler = mock(OSHandler.class);
        public final List<Task> executedTasks = new java.util.ArrayList<>();
        public final List<BecomeContext> executedContexts = new java.util.ArrayList<>();

        @Override public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, Connection connection, ConnectionFactory connectionFactory) {
            TaskExecutor realExecutor = new TaskExecutor() {
                @Override
                public TaskResult execute(Task t, BecomeContext bc, Map<String, String> env) {
                    executedTasks.add(t);
                    executedContexts.add(bc);
                    return TaskResult.success(Map.of());
                }

                @Override
                protected TaskResult executeActionPlugin(Task t, BecomeContext bc, Connection conn, Map<String, String> env, Map<String, Object> vars) {
                    executedTasks.add(t);
                    executedContexts.add(bc);
                    return TaskResult.success(Map.of());
                }
            };
            return realExecutor.execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironments, blockVars, connection, connectionFactory);
        }

        @Override
        public VariableResolver getVariableResolver() {
            return new VariableResolver();
        }

        @Override
        public VariableManager getVariableManager() {
            return null;
        }

        @Override
        public String resolveLocalPath(String path) {
            return path;
        }

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) {
            return TaskResult.success(Map.of());
        }

        @Override
        public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
            return TaskResult.success(Map.of());
        }

        @Override
        public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) {
            return Map.of();
        }

        @Override
        public OSHandler getOsHandler() {
            return osHandler;
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

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of("ansible_connection", "local"));
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

    @Test
    void testBecomeCliResolution() {
        String yaml = """
            - name: Play without become
              hosts: all
              tasks:
                - name: Task without become
                  debug:
                    msg: hello
                - name: Task with explicit become no
                  debug:
                    msg: world
                  become: no
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        // Simulate CLI -b --become-user=operator
        Map<String, Object> cliVars = Map.of(
                "ansible_become", true,
                "ansible_become_user", "operator"
        );
        VariableManager vm = new VariableManager(inventory, cliVars, Map.of(), null, null);

        playbookExecutor.execute(playbook, inventory, vm, false);

        List<BecomeContext> contexts = taskExecutor.executedContexts;
        assertEquals(2, contexts.size());

        // Task 1: respects CLI variables
        assertTrue(contexts.get(0).become(), "Should become because of CLI variable");
        assertEquals("operator", contexts.get(0).becomeUser());

        // Task 2: overrides CLI variable with become: no
        assertFalse(contexts.get(1).become(), "Task should override CLI variable with become: no");
    }

    @Test
    void testBecomePasswordResolution() {
        String yaml = """
            - name: Play with become password
              hosts: all
              become: yes
              tasks:
                - name: Task checking password
                  debug:
                    msg: hello
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        // Scenario 1: ansible_become_password
        Map<String, Object> cliVars1 = Map.of(
                "ansible_become_password", "secret1"
        );
        VariableManager vm1 = new VariableManager(inventory, cliVars1, Map.of(), null, null);
        playbookExecutor.execute(playbook, inventory, vm1, false);

        assertEquals("secret1", taskExecutor.executedContexts.get(0).becomePassword());

        // Scenario 2: ansible_become_pass (alias)
        taskExecutor.executedContexts.clear();
        Map<String, Object> cliVars2 = Map.of(
                "ansible_become_pass", "secret2"
        );
        VariableManager vm2 = new VariableManager(inventory, cliVars2, Map.of(), null, null);
        playbookExecutor.execute(playbook, inventory, vm2, false);

        assertEquals("secret2", taskExecutor.executedContexts.get(0).becomePassword());
    }

    @Test
    void testBecomeMethodAndFlagsResolution() {
        String yaml = """
            - name: Play with method and flags
              hosts: all
              become: yes
              become_method: su
              become_flags: "-s /bin/bash"
              vars:
                custom_flags: "-H -S"
                custom_method: pbrun
              tasks:
                - name: Task inheriting play method and flags
                  debug:
                    msg: hello
                - name: Task overriding method and flags with variables
                  debug:
                    msg: world
                  become_method: "{{ custom_method }}"
                  become_flags: "{{ custom_flags }}"
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        playbookExecutor.execute(playbook, inventory);

        List<BecomeContext> contexts = taskExecutor.executedContexts;
        assertEquals(2, contexts.size());

        // Task 1: inherits play become_method and become_flags
        assertTrue(contexts.get(0).become());
        assertEquals("su", contexts.get(0).becomeMethod());
        assertEquals("-s /bin/bash", contexts.get(0).becomeFlags());

        // Task 2: overrides become_method and become_flags via variables
        assertTrue(contexts.get(1).become());
        assertEquals("pbrun", contexts.get(1).becomeMethod());
        assertEquals("-H -S", contexts.get(1).becomeFlags());
    }

    @Test
    void testBecomeDefaults() {
        String yaml = """
            - name: Play with minimal become
              hosts: all
              become: yes
              tasks:
                - name: Task with default become settings
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
        assertEquals("sudo", context.becomeMethod(), "Default become_method should be 'sudo'");
        assertEquals("root", context.becomeUser(), "Default become_user should be 'root'");
        assertEquals("", context.becomeFlags(), "Default become_flags should be empty string");
    }

    @Test
    void testBecomeCliMethodAndFlagsResolution() {
        String yaml = """
            - name: Play without explicit method or flags
              hosts: all
              become: yes
              tasks:
                - name: Task using CLI settings
                  debug:
                    msg: hello
            """;

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        MockTaskExecutor taskExecutor = new MockTaskExecutor();
        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor);

        org.example.ansible.inventory.Group allGroup = new org.example.ansible.inventory.Group("all", List.of(new org.example.ansible.inventory.Host("localhost")), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        Map<String, Object> cliVars = Map.of(
                "ansible_become_method", "su",
                "ansible_become_flags", "-m"
        );
        VariableManager vm = new VariableManager(inventory, cliVars, Map.of(), null, null);

        playbookExecutor.execute(playbook, inventory, vm, false);

        assertEquals(1, taskExecutor.executedContexts.size());
        BecomeContext context = taskExecutor.executedContexts.get(0);

        assertTrue(context.become());
        assertEquals("su", context.becomeMethod(), "CLI ansible_become_method should be used");
        assertEquals("-m", context.becomeFlags(), "CLI ansible_become_flags should be used");
    }
}
