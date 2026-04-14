package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicInventoryIntegrationTest {

    private TaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private VariableManager variableManager;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());

        Host localhost = new Host("localhost");
        Group all = new Group("all", List.of(localhost), new ArrayList<>(), new HashMap<>());
        inventory = new Inventory(all);
        variableManager = new VariableManager(inventory, Map.of());
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testAddHost() {
        // Play 1: Add a new host
        Task addHostTask = new Task("add new host", "add_host", Map.of(
                "name", "new_host",
                "groups", "dynamic_group",
                "custom_var", "custom_value"
        ));
        Play play1 = new Play("Play 1", "localhost", List.of(addHostTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play1, inventory, variableManager, results, false);

        assertTrue(results.get("localhost").get(0).success());

        // Verify inventory update
        Host newHost = inventory.findHost("new_host").orElse(null);
        assertNotNull(newHost);
        assertEquals("custom_value", newHost.variables().get("custom_var"));

        Group dynamicGroup = inventory.findGroup(inventory.all(), "dynamic_group");
        assertNotNull(dynamicGroup);
        assertTrue(dynamicGroup.hosts().stream().anyMatch(h -> h.name().equals("new_host")));

        // Play 2: Target the new host
        Task pingTask = new Task("ping new host", "ping", Map.of());
        Play play2 = new Play("Play 2", "dynamic_group", List.of(pingTask));

        tqm.executePlay(play2, inventory, variableManager, results, false);

        assertNotNull(results.get("new_host"));
        assertTrue(results.get("new_host").get(0).success());
    }

    @Test
    void testGroupBy() {
        // Play 1: Group by OS family (simulated)
        // We'll manually set a fact for localhost first
        variableManager.addFacts("localhost", Map.of("os_family", "Debian"));

        Task groupByTask = new Task("group by os", "group_by", Map.of(
                "key", "os_{{ os_family }}"
        ));
        Play play1 = new Play("Play 1", "localhost", List.of(groupByTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play1, inventory, variableManager, results, false);

        assertTrue(results.get("localhost").get(0).success());

        // Verify inventory update
        Group newGroup = inventory.findGroup(inventory.all(), "os_Debian");
        assertNotNull(newGroup);
        assertTrue(newGroup.hosts().stream().anyMatch(h -> h.name().equals("localhost")));

        // Play 2: Target the new group
        Task debugTask = new Task("debug new group", "debug", Map.of("msg", "In new group"));
        Play play2 = new Play("Play 2", "os_Debian", List.of(debugTask));

        tqm.executePlay(play2, inventory, variableManager, results, false);

        assertNotNull(results.get("localhost"));
        // localhost should have 2 results now: 1 from play1, 1 from play2
        assertEquals(2, results.get("localhost").size());
        assertEquals("In new group", results.get("localhost").get(1).data().get("msg"));
    }
}
