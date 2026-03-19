package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskControlAdvancedTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;
    private ConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connectionFactory = mock(ConnectionFactory.class);
        playbookExecutor = new PlaybookExecutor(taskExecutor, connectionFactory);
    }

    @Test
    void testIgnoreUnreachable() {
        String inventoryYaml = """
                all:
                  hosts:
                    unreachable_host: {}
                    reachable_host: {}
                """;
        Inventory inventory = new org.example.ansible.inventory.YamlInventoryParser().parse(new ByteArrayInputStream(inventoryYaml.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test ignore_unreachable
                  hosts: all
                  tasks:
                    - name: task 1
                      ping:
                      ignore_unreachable: true
                    - name: task 2
                      ping:
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Connection reachableConn = mock(Connection.class);
        Connection unreachableConn = mock(Connection.class);
        doThrow(new UnreachableException("Connection failed")).when(unreachableConn).connect();

        when(connectionFactory.createConnection(any(), any())).thenAnswer(invocation -> {
            Host h = invocation.getArgument(0);
            if (h != null && "reachable_host".equals(h.name())) return reachableConn;
            if (h != null && "unreachable_host".equals(h.name())) return unreachableConn;
            return null;
        });

        taskExecutor.registerModule("ping", (args, become, context) -> TaskResult.success(Map.of("ping", "pong", "changed", false)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // unreachable_host should have 1 unreachable result and no more
        assertTrue(results.containsKey("unreachable_host"), "unreachable_host should have results");
        assertEquals(1, results.get("unreachable_host").size());
        assertTrue(results.get("unreachable_host").get(0).isUnreachable());

        // reachable_host should have 2 results
        assertTrue(results.containsKey("reachable_host"), "reachable_host should have results");
        assertEquals(2, results.get("reachable_host").size());
    }

    @Test
    void testDelegateFacts() {
        String inventoryYaml = """
                all:
                  hosts:
                    host1: {}
                    host2: {}
                """;
        Inventory inventory = new org.example.ansible.inventory.YamlInventoryParser().parse(new ByteArrayInputStream(inventoryYaml.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test delegate_facts
                  hosts: all
                  tasks:
                    - name: get facts on host2 for host1
                      my_setup:
                      delegate_to: host2
                      delegate_facts: true
                      when: inventory_hostname == 'host1'
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Connection host1Conn = mock(Connection.class);
        Connection host2Conn = mock(Connection.class);

        when(connectionFactory.createConnection(any(), any())).thenAnswer(invocation -> {
            Host h = invocation.getArgument(0);
            if (h != null && "host1".equals(h.name())) return host1Conn;
            if (h != null && "host2".equals(h.name())) return host2Conn;
            return null;
        });

        taskExecutor.registerModule("my_setup", (args, become, context) ->
            TaskResult.success(Map.of("ansible_facts", Map.of("discovered_fact", "value"), "changed", false))
        );

        // Provide host2 variable so delegate_to: host2 resolves to "host2"
        VariableManager vm = new VariableManager(inventory, Map.of("host2", "host2"));
        playbookExecutor.execute(playbook, inventory, vm, false);

        // host1 should have the fact, host2 should not
        Map<String, Object> host1RuntimeVars = vm.getHostRuntimeVariables("host1");
        assertEquals("value", host1RuntimeVars.get("discovered_fact"), "Fact should be assigned to host1");

        Map<String, Object> host2RuntimeVars = vm.getHostRuntimeVariables("host2");
        assertNull(host2RuntimeVars.get("discovered_fact"), "Fact should not be assigned to host2");
    }

    @Test
    void testDelegateFactsFalse() {
        String inventoryYaml = """
                all:
                  hosts:
                    host1: {}
                    host2: {}
                """;
        Inventory inventory = new org.example.ansible.inventory.YamlInventoryParser().parse(new ByteArrayInputStream(inventoryYaml.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test delegate_facts false
                  hosts: all
                  tasks:
                    - name: get facts on host2 for host2
                      my_setup:
                      delegate_to: host2
                      delegate_facts: false
                      when: inventory_hostname == 'host1'
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Connection host1Conn = mock(Connection.class);
        Connection host2Conn = mock(Connection.class);

        when(connectionFactory.createConnection(any(), any())).thenAnswer(invocation -> {
            Host h = invocation.getArgument(0);
            if (h != null && "host1".equals(h.name())) return host1Conn;
            if (h != null && "host2".equals(h.name())) return host2Conn;
            return null;
        });

        taskExecutor.registerModule("my_setup", (args, become, context) ->
            TaskResult.success(Map.of("ansible_facts", Map.of("discovered_fact", "value"), "changed", false))
        );

        VariableManager vm = new VariableManager(inventory, Map.of("host2", "host2"));
        playbookExecutor.execute(playbook, inventory, vm, false);

        // host2 should have the fact, host1 should not
        Map<String, Object> host2RuntimeVars = vm.getHostRuntimeVariables("host2");
        assertEquals("value", host2RuntimeVars.get("discovered_fact"), "Fact should be assigned to host2");

        Map<String, Object> host1RuntimeVars = vm.getHostRuntimeVariables("host1");
        assertNull(host1RuntimeVars.get("discovered_fact"), "Fact should not be assigned to host1");
    }
}
