package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Comprehensive test for the 22-level variable precedence hierarchy.
 */
class VariablePrecedenceComprehensiveTest {

    @TempDir
    Path tempDir;

    @Test
    void testPrecedenceLevels() throws IOException {
        // We will test several key levels to ensure they follow the documented order.
        // Higher number = Higher priority.

        // Setup base directories
        Path pbDir = tempDir.resolve("playbook");
        Files.createDirectories(pbDir.resolve("group_vars"));
        Files.createDirectories(pbDir.resolve("host_vars"));
        Path invDir = tempDir.resolve("inventory");
        Files.createDirectories(invDir.resolve("group_vars"));
        Files.createDirectories(invDir.resolve("host_vars"));

        // Level 2: Role defaults
        // Level 3: Inventory group variables
        // Level 4: Inventory group_vars/all
        // Level 5: Playbook group_vars/all
        // Level 8: Inventory host variables
        // Level 12: Play vars
        // Level 15: Role vars
        // Level 17: Task vars
        // Level 20: Role parameters
        // Level 22: Extra variables

        Host host = new Host("host1", Map.of("v", "level8_inv_host"));
        Group all = new Group("all", List.of(host), List.of(), Map.of("v", "level4_inv_group_all"));
        Inventory inventory = new Inventory(all);

        Files.writeString(invDir.resolve("group_vars/all.yml"), "v: level4_inv_group_all_file");
        Files.writeString(pbDir.resolve("group_vars/all.yml"), "v: level5_pb_group_all_file");

        Map<String, Object> cliVars = Map.of("ansible_run_tags", List.of("tag1"));
        Map<String, Object> extraVars = Map.of("v", "level22_extra");

        VariableManager vm = new VariableManager(inventory, cliVars, extraVars, pbDir, invDir);

        Role role = new Role("role1", Map.of("v", "level20_role_param"));
        vm.addRoleDefaults("role1", Map.of("v", "level2_role_default"));
        vm.addRoleVars("role1", Map.of("v", "level15_role_var"));

        Play play = new Play("play", "all", List.of(), Map.of("v", "level12_play_var"), List.of(), List.of(), List.of(role), List.of(), null, null, null, null, null, null, List.of());
        Task task = new Task("task", "debug", Map.of(), Map.of("v", "level17_task_var"));

        // Act
        Map<String, Object> vars = vm.getAllVariables(play, host, task, null, List.of(role), null);

        // Assert
        assertEquals("level22_extra", vars.get("v"), "Extra vars (Level 22) should win");
        assertEquals(List.of("tag1"), vars.get("ansible_run_tags"), "Magic variable ansible_run_tags should be propagated");

        // Verify without Level 22
        vm = new VariableManager(inventory, cliVars, Map.of(), pbDir, invDir);
        vm.addRoleDefaults("role1", Map.of("v", "level2_role_default"));
        vm.addRoleVars("role1", Map.of("v", "level15_role_var"));
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level20_role_param", vars.get("v"), "Role parameters (Level 20) should win over Level 17");

        // Verify without Level 20
        role = new Role("role1", Map.of());
        play = new Play("play", "all", List.of(), Map.of("v", "level12_play_var"), List.of(), List.of(), List.of(role), List.of(), null, null, null, null, null, null, List.of());
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level17_task_var", vars.get("v"), "Task vars (Level 17) should win over Level 15");

        // Verify without Level 17
        task = new Task("task", "debug", Map.of(), Map.of());
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level15_role_var", vars.get("v"), "Role vars (Level 15) should win over Level 12");

        // Verify without Level 15
        vm = new VariableManager(inventory, cliVars, Map.of(), pbDir, invDir);
        vm.addRoleDefaults("role1", Map.of("v", "level2_role_default"));
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level12_play_var", vars.get("v"), "Play vars (Level 12) should win over Level 8");

        // Verify without Level 12
        play = new Play("play", "all", List.of(), Map.of(), List.of(), List.of(), List.of(role), List.of(), null, null, null, null, null, null, List.of());
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level8_inv_host", vars.get("v"), "Inventory host vars (Level 8) should win over Level 5");

        // Verify without Level 8 (host vars)
        host = new Host("host1", Map.of());
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of("v", "level3_inv_group")));
        vm = new VariableManager(inventory, cliVars, Map.of(), pbDir, invDir);
        vm.addRoleDefaults("role1", Map.of("v", "level2_role_default"));
        vars = vm.getAllVariables(play, host, task, null, List.of(role), null);
        assertEquals("level5_pb_group_all_file", vars.get("v"), "Playbook group_vars/all (Level 5) should win over Level 4");
    }
}
