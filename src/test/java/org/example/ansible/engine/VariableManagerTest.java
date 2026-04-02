package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableManagerTest {

    @Test
    void testVariablePriority() {
        // Inventory variables
        Host host = new Host("host1", Map.of("my_var", "host_var"));
        Group group = new Group("group1", List.of(host), List.of(), Map.of("my_var", "group_var"));
        Inventory inventory = new Inventory(group);

        // Play variables
        Play play = new Play("play1", "all", List.of(), Map.of("my_var", "play_var"));

        // Task variables
        Task task = new Task("task1", "debug", Map.of(), Map.of("my_var", "task_var"));

        // Extra variables
        Map<String, Object> extraVars = Map.of("my_var", "extra_var");

        VariableManager manager = new VariableManager(inventory, extraVars);

        // Priority: Extra > Task > Play > Inventory(Host)
        Map<String, Object> vars = manager.getAllVariables(play, host, task, null);
        assertEquals("extra_var", vars.get("my_var"));

        // Remove extra
        manager = new VariableManager(inventory, Map.of());
        vars = manager.getAllVariables(play, host, task, null);
        assertEquals("task_var", vars.get("my_var"));

        // Remove task var
        vars = manager.getAllVariables(play, host, null, null);
        assertEquals("play_var", vars.get("my_var"));

        // Remove play var
        play = new Play("play1", "all", List.of(), Map.of());
        vars = manager.getAllVariables(play, host, null, null);
        assertEquals("host_var", vars.get("my_var"));
    }

    @Test
    void testMagicVariables() {
        Host host = new Host("host1");
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        java.nio.file.Path baseDir = java.nio.file.Path.of("/tmp/playbook");
        java.nio.file.Path inventoryDir = java.nio.file.Path.of("/tmp/inventory");

        Map<String, Object> cliVars = Map.of("ansible_verbosity", 3, "ansible_check_mode", true);

        VariableManager manager = new VariableManager(inventory, cliVars, Map.of(), baseDir, inventoryDir);

        Map<String, Object> vars = manager.getAllVariables(null, host, null, null);

        assertEquals("host1", vars.get("inventory_hostname"));
        assertEquals(baseDir.toAbsolutePath().toString(), vars.get("playbook_dir"));
        assertEquals(inventoryDir.toAbsolutePath().toString(), vars.get("inventory_dir"));
        assertEquals(3, vars.get("ansible_verbosity"));
        assertEquals(true, vars.get("ansible_check_mode"));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> groups = (Map<String, List<String>>) vars.get("groups");
        assertTrue(groups.containsKey("group1"));
        assertTrue(groups.get("group1").contains("host1"));

        @SuppressWarnings("unchecked")
        List<String> groupNames = (List<String>) vars.get("group_names");
        assertTrue(groupNames.contains("group1"));
    }
}
