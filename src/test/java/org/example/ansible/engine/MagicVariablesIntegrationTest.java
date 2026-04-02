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

import static org.junit.jupiter.api.Assertions.*;

class MagicVariablesIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testMagicVariablesInTask() throws IOException {
        Path playbookDir = tempDir.resolve("playbook");
        Files.createDirectories(playbookDir);

        Path inventoryDir = tempDir.resolve("inventory");
        Files.createDirectories(inventoryDir);

        Host host = new Host("host1");
        Group group = new Group("group1", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(group);

        Map<String, Object> cliVars = Map.of("ansible_verbosity", 2, "ansible_check_mode", false);
        VariableManager manager = new VariableManager(inventory, cliVars, Map.of(), playbookDir, inventoryDir);

        // Task that uses magic variables in its arguments
        Task task = new Task("test_magic", "debug", Map.of(
            "pb_dir", "{{ playbook_dir }}",
            "inv_dir", "{{ inventory_dir }}",
            "host", "{{ inventory_hostname }}",
            "verbosity", "{{ ansible_verbosity }}"
        ));

        VariableResolver resolver = new VariableResolver();
        Map<String, Object> allVars = manager.getAllVariables(null, host, task, null);
        Map<String, Object> resolvedArgs = resolver.resolve(task.args(), allVars);

        assertEquals(playbookDir.toAbsolutePath().toString(), resolvedArgs.get("pb_dir"));
        assertEquals(inventoryDir.toAbsolutePath().toString(), resolvedArgs.get("inv_dir"));
        assertEquals("host1", resolvedArgs.get("host"));
        assertEquals(2, resolvedArgs.get("verbosity"));
    }
}
