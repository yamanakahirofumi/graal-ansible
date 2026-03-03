package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        Map<String, Object> vars = manager.getAllVariables(play, host, task);
        assertEquals("extra_var", vars.get("my_var"));

        // Remove extra
        manager = new VariableManager(inventory, Map.of());
        vars = manager.getAllVariables(play, host, task);
        assertEquals("task_var", vars.get("my_var"));

        // Remove task var
        vars = manager.getAllVariables(play, host, null);
        assertEquals("play_var", vars.get("my_var"));

        // Remove play var
        play = new Play("play1", "all", List.of(), Map.of());
        vars = manager.getAllVariables(play, host, null);
        assertEquals("host_var", vars.get("my_var"));
    }
}
