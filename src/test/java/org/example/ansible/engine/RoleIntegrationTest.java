package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testRoleExecutionAndPrecedence() throws IOException {
        // Setup role directory structure
        Path rolesDir = tempDir.resolve("roles");
        Path testRoleDir = rolesDir.resolve("test_role");
        Files.createDirectories(testRoleDir.resolve("defaults"));
        Files.createDirectories(testRoleDir.resolve("vars"));
        Files.createDirectories(testRoleDir.resolve("tasks"));

        Files.writeString(testRoleDir.resolve("defaults").resolve("main.yml"),
                "def_var: default_val\n" +
                "override_var: default_val");

        Files.writeString(testRoleDir.resolve("vars").resolve("main.yml"),
                "vars_var: vars_val\n" +
                "override_var: role_vars_val");

        Files.writeString(testRoleDir.resolve("tasks").resolve("main.yml"),
                "- name: role task 1\n" +
                "  debug:\n" +
                "    msg: \"Role task 1 executed\"\n" +
                "- name: role task 2\n" +
                "  set_fact:\n" +
                "    role_fact: \"role_fact_val\"\n" +
                "- name: check precedence\n" +
                "  debug:\n" +
                "    msg: \"{{ override_var }}\"");

        // Setup inventory
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        // Setup Play with the role
        Play play = new Play("play with role", "all",
                List.of(new Task("play task", "debug", Map.of("msg", "Play task executed"))),
                Map.of("override_var", "play_vars_val") // Level 12
        );
        // Add role manually since Play is a record and we updated it
        Play playWithRole = new Play(play.name(), play.hosts(), play.tasks(), play.vars(), play.varsFiles(),
                List.of(), List.of(new Role("test_role")), play.handlers(), play.become(), play.becomeMethod(),
                play.becomeUser(), play.becomeFlags(), play.checkMode(), play.environment(), play.tags());

        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        // Use a mock/local executor
        TaskExecutor taskExecutor = new TaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(playWithRole, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        // Role tasks (3) + Play task (1) = 4
        assertEquals(4, hostResults.size(), "Should have 4 task results");

        // Verify role task 1
        assertEquals("Role task 1 executed", hostResults.get(0).data().get("msg"));

        // Verify role task 2 (set_fact)
        assertTrue(hostResults.get(1).success());
        assertEquals("role_fact_val", vm.getVariablesForHost("localhost").get("role_fact"));

        // Verify precedence (Level 15 Role vars should beat Level 12 Play vars)
        assertEquals("role_vars_val", hostResults.get(2).data().get("msg"), "Level 15 (Role vars) should beat Level 12 (Play vars)");

        // Verify play task
        assertEquals("Play task executed", hostResults.get(3).data().get("msg"));

        taskExecutor.close();
    }

    @Test
    void testRoleParameters() throws IOException {
        // Setup role with parameters
        Path rolesDir = tempDir.resolve("roles");
        Path paramRoleDir = rolesDir.resolve("param_role");
        Files.createDirectories(paramRoleDir.resolve("tasks"));
        Files.writeString(paramRoleDir.resolve("tasks").resolve("main.yml"),
                "- debug:\n" +
                "    msg: \"{{ param_var }}\"");

        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        Role roleWithParams = new Role("param_role", Map.of("param_var", "param_val"));
        Play play = new Play("play with param role", "all", List.of());
        Play playWithRole = new Play(play.name(), play.hosts(), play.tasks(), play.vars(), play.varsFiles(),
                List.of(), List.of(roleWithParams), play.handlers(), play.become(), play.becomeMethod(),
                play.becomeUser(), play.becomeFlags(), play.checkMode(), play.environment(), play.tags());

        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);
        TaskExecutor taskExecutor = new TaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(playWithRole, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertEquals("param_val", hostResults.get(0).data().get("msg"), "Role parameters (Level 20) should be available");

        taskExecutor.close();
    }

    @Test
    void testIncludeRole() throws IOException {
        // Setup role directory structure
        Path rolesDir = tempDir.resolve("roles");
        Path testRoleDir = rolesDir.resolve("included_role");
        Files.createDirectories(testRoleDir.resolve("tasks"));
        Files.createDirectories(testRoleDir.resolve("vars"));

        Files.writeString(testRoleDir.resolve("vars").resolve("main.yml"), "role_var: role_val");
        Files.writeString(testRoleDir.resolve("tasks").resolve("main.yml"),
                "- debug: { msg: \"{{ role_var }} and {{ param_var }}\" }");

        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        // Play that uses include_role
        Play play = new Play("play with include_role", "all", List.of(
                new Task("include role", "include_role", Map.of(
                        "name", "included_role",
                        "param_var", "param_val"
                ))
        ));

        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);
        TaskExecutor taskExecutor = new TaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        // include_role task itself doesn't produce a result in the list if it just includes other tasks
        // However, our implementation adds role tasks to the results.
        // First result is from debug inside the role.
        assertEquals(1, hostResults.size());
        assertEquals("role_val and param_val", hostResults.get(0).data().get("msg"));

        taskExecutor.close();
    }

    @Test
    void testIncludeRoleTasksFrom() throws IOException {
        Path rolesDir = tempDir.resolve("roles");
        Path testRoleDir = rolesDir.resolve("tasks_from_role");
        Files.createDirectories(testRoleDir.resolve("tasks"));

        Files.writeString(testRoleDir.resolve("tasks").resolve("other.yml"),
                "- debug: { msg: \"other tasks executed\" }");

        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        Play play = new Play("play with include_role tasks_from", "all", List.of(
                new Task("include role", "include_role", Map.of(
                        "name", "tasks_from_role",
                        "tasks_from", "other"
                ))
        ));

        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);
        TaskExecutor taskExecutor = new TaskExecutor();
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertEquals("other tasks executed", hostResults.get(0).data().get("msg"));

        taskExecutor.close();
    }
}
