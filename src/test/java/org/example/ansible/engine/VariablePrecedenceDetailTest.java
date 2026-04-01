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

class VariablePrecedenceDetailTest {

    @TempDir
    Path tempDir;

    @Test
    void testLevel8BeatsLevel7() throws IOException {
        // Level 8: Inventory host variables
        Host host = new Host("host1", Map.of("test_var", "level8_host_var"));
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        // Level 7: Playbook group_vars/group1
        Path playbookDir = tempDir.resolve("playbook");
        Path groupVarsDir = playbookDir.resolve("group_vars");
        Files.createDirectories(groupVarsDir);
        Files.writeString(groupVarsDir.resolve("group1.yml"), "test_var: level7_group_var");

        VariableManager manager = new VariableManager(inventory, Map.of(), playbookDir, null);
        Play play = new Play("play1", "all", List.of());

        Map<String, Object> vars = manager.getAllVariables(play, host, null, null);

        // Expected: Level 8 (host var) should beat Level 7 (playbook group_vars)
        assertEquals("level8_host_var", vars.get("test_var"), "Level 8 (Inventory host variables) should beat Level 7 (Playbook group_vars)");
    }

    @Test
    void testLevel7BeatsLevel6() throws IOException {
        // Level 6: Inventory group_vars/group1
        Path inventoryDir = tempDir.resolve("inventory");
        Path invGroupVarsDir = inventoryDir.resolve("group_vars");
        Files.createDirectories(invGroupVarsDir);
        Files.writeString(invGroupVarsDir.resolve("group1.yml"), "test_var: level6_inv_group_var");

        // Level 7: Playbook group_vars/group1
        Path playbookDir = tempDir.resolve("playbook");
        Path pbGroupVarsDir = playbookDir.resolve("group_vars");
        Files.createDirectories(pbGroupVarsDir);
        Files.writeString(pbGroupVarsDir.resolve("group1.yml"), "test_var: level7_pb_group_var");

        Host host = new Host("host1");
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        VariableManager manager = new VariableManager(inventory, Map.of(), playbookDir, inventoryDir);
        Play play = new Play("play1", "all", List.of());

        Map<String, Object> vars = manager.getAllVariables(play, host, null, null);

        assertEquals("level7_pb_group_var", vars.get("test_var"), "Level 7 (Playbook group_vars) should beat Level 6 (Inventory group_vars)");
    }

    @Test
    void testLevel10BeatsLevel9() throws IOException {
        // Level 9: Inventory host_vars/host1
        Path inventoryDir = tempDir.resolve("inventory");
        Path invHostVarsDir = inventoryDir.resolve("host_vars");
        Files.createDirectories(invHostVarsDir);
        Files.writeString(invHostVarsDir.resolve("host1.yml"), "test_var: level9_inv_host_var");

        // Level 10: Playbook host_vars/host1
        Path playbookDir = tempDir.resolve("playbook");
        Path pbHostVarsDir = playbookDir.resolve("host_vars");
        Files.createDirectories(pbHostVarsDir);
        Files.writeString(pbHostVarsDir.resolve("host1.yml"), "test_var: level10_pb_host_var");

        Host host = new Host("host1");
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        VariableManager manager = new VariableManager(inventory, Map.of(), playbookDir, inventoryDir);
        Play play = new Play("play1", "all", List.of());

        Map<String, Object> vars = manager.getAllVariables(play, host, null, null);

        assertEquals("level10_pb_host_var", vars.get("test_var"), "Level 10 (Playbook host_vars) should beat Level 9 (Inventory host_vars)");
    }

    @Test
    void testLevel9BeatsLevel8() throws IOException {
        // Level 8: Inventory host variables
        Host host = new Host("host1", Map.of("test_var", "level8_host_var"));
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        // Level 9: Inventory host_vars/host1
        Path inventoryDir = tempDir.resolve("inventory");
        Path invHostVarsDir = inventoryDir.resolve("host_vars");
        Files.createDirectories(invHostVarsDir);
        Files.writeString(invHostVarsDir.resolve("host1.yml"), "test_var: level9_inv_host_var");

        VariableManager manager = new VariableManager(inventory, Map.of(), null, inventoryDir);
        Play play = new Play("play1", "all", List.of());

        Map<String, Object> vars = manager.getAllVariables(play, host, null, null);

        assertEquals("level9_inv_host_var", vars.get("test_var"), "Level 9 (Inventory host_vars) should beat Level 8 (Inventory host variables)");
    }
}
