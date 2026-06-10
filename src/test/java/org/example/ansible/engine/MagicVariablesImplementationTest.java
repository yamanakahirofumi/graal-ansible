package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MagicVariablesImplementationTest {

    @Test
    @SuppressWarnings("unchecked")
    void testAnsibleVersion() {
        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        VariableManager manager = new VariableManager(inventory, Map.of());
        Map<String, Object> vars = manager.getAllVariables(null, new Host("localhost"), null, null);

        assertTrue(vars.containsKey("ansible_version"), "ansible_version should be present");
        Object version = vars.get("ansible_version");
        assertTrue(version instanceof Map, "ansible_version should be a Map");
        Map<String, Object> versionMap = (Map<String, Object>) version;
        assertEquals("2.21.0", versionMap.get("full"));
        assertEquals(2, versionMap.get("major"));
        assertEquals(21, versionMap.get("minor"));
        assertEquals(0, versionMap.get("revision"));
        assertEquals("2.21.0", versionMap.get("string"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHostvars() {
        Host host1 = new Host("host1", new java.util.HashMap<>(Map.of("var1", "value1")));
        Host host2 = new Host("host2", new java.util.HashMap<>(Map.of("var2", "value2")));
        Group all = new Group("all", new java.util.ArrayList<>(List.of(host1, host2)), new java.util.ArrayList<>(), new java.util.HashMap<>());
        Inventory inventory = new Inventory(all);
        VariableManager manager = new VariableManager(inventory, Map.of());

        Map<String, Object> vars1 = manager.getAllVariables(null, host1, null, null);
        assertTrue(vars1.containsKey("hostvars"), "hostvars should be present");

        Map<String, Object> hostvars = (Map<String, Object>) vars1.get("hostvars");
        assertTrue(hostvars.containsKey("host2"), "hostvars should contain host2");

        Map<String, Object> vars2FromHostvars = (Map<String, Object>) hostvars.get("host2");
        assertNotNull(vars2FromHostvars, "vars for host2 should not be null");
        assertEquals("value2", vars2FromHostvars.get("var2"));
        assertEquals("host2", vars2FromHostvars.get("inventory_hostname"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPlayHostsVariables() {
        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");
        Group all = new Group("all", new java.util.ArrayList<>(List.of(host1, host2, host3)), new java.util.ArrayList<>(), new java.util.HashMap<>());
        Inventory inventory = new Inventory(all);
        VariableManager manager = new VariableManager(inventory, Map.of());

        List<String> targetHosts = List.of("host1", "host2");
        Set<String> failedHosts = new HashSet<>();
        failedHosts.add("host1");

        manager.setPlayContext(targetHosts, failedHosts);

        Map<String, Object> vars = manager.getAllVariables(null, host2, null, null);

        assertEquals(targetHosts, vars.get("ansible_play_hosts_all"));
        assertEquals(List.of("host2"), vars.get("ansible_play_hosts"));
        assertEquals(List.of("host2"), vars.get("ansible_play_batch"));
    }

    @Test
    void testDiffModeMagicVariable() {
        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        Map<String, Object> cliVars = Map.of("ansible_diff_mode", true);
        VariableManager manager = new VariableManager(inventory, cliVars, Map.of(), null, null);

        Map<String, Object> vars = manager.getAllVariables(null, new Host("localhost"), null, null);
        assertEquals(true, vars.get("ansible_diff_mode"));
    }
}
