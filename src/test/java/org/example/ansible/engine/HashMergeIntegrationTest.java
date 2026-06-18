package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashMergeIntegrationTest {

    @Test
    void testDefaultReplaceBehaviour() {
        Host host = new Host("localhost");
        Group all = new Group("all", List.of(host), List.of(), Map.of(
                "my_dict", Map.of("key1", "val1", "key2", "val2")
        ));
        Inventory inventory = new Inventory(all);

        // Play vars should completely replace the dict
        Play play = new Play("Test Play", "all", List.of(), Map.of(
                "my_dict", Map.of("key3", "val3")
        ));

        VariableManager vm = new VariableManager(inventory, Map.of(), Map.of(), null, null, VariableManager.HashBehaviour.REPLACE);
        Map<String, Object> vars = vm.getAllVariables(play, host, null, null);

        Map<String, Object> resultDict = (Map<String, Object>) vars.get("my_dict");
        assertEquals(1, resultDict.size());
        assertEquals("val3", resultDict.get("key3"));
    }

    @Test
    void testMergeBehaviour() {
        Host host = new Host("localhost");
        Group all = new Group("all", List.of(host), List.of(), Map.of(
                "my_dict", Map.of("key1", "val1", "key2", "val2")
        ));
        Inventory inventory = new Inventory(all);

        // Play vars should merge with the existing dict
        Play play = new Play("Test Play", "all", List.of(), Map.of(
                "my_dict", Map.of("key2", "new_val2", "key3", "val3")
        ));

        VariableManager vm = new VariableManager(inventory, Map.of(), Map.of(), null, null, VariableManager.HashBehaviour.MERGE);
        Map<String, Object> vars = vm.getAllVariables(play, host, null, null);

        Map<String, Object> resultDict = (Map<String, Object>) vars.get("my_dict");
        assertEquals(3, resultDict.size());
        assertEquals("val1", resultDict.get("key1"));
        assertEquals("new_val2", resultDict.get("key2"));
        assertEquals("val3", resultDict.get("key3"));
    }

    @Test
    void testDeepMergeBehaviour() {
        Host host = new Host("localhost");
        Group all = new Group("all", List.of(host), List.of(), Map.of(
                "nested_dict", Map.of("level1", Map.of("key1", "val1", "key2", "val2"))
        ));
        Inventory inventory = new Inventory(all);

        Play play = new Play("Test Play", "all", List.of(), Map.of(
                "nested_dict", Map.of("level1", Map.of("key2", "new_val2", "key3", "val3"))
        ));

        VariableManager vm = new VariableManager(inventory, Map.of(), Map.of(), null, null, VariableManager.HashBehaviour.MERGE);
        Map<String, Object> vars = vm.getAllVariables(play, host, null, null);

        Map<String, Object> nested = (Map<String, Object>) vars.get("nested_dict");
        Map<String, Object> level1 = (Map<String, Object>) nested.get("level1");

        assertEquals(3, level1.size());
        assertEquals("val1", level1.get("key1"));
        assertEquals("new_val2", level1.get("key2"));
        assertEquals("val3", level1.get("key3"));
    }
}
