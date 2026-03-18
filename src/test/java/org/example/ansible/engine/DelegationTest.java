package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Group;
import org.example.ansible.util.OSHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DelegationTest {

    @Test
    void testDelegateToLocalhost() {
        List<Connection> capturedConnections = new ArrayList<>();

        TaskExecutor taskExecutor = new TaskExecutor() {
            @Override
            public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
                capturedConnections.add(connection);
                return TaskResult.success(Map.of());
            }
        };
        taskExecutor.registerModule("ping", (args, bc, ctx) -> TaskResult.success(Map.of()));

        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());

        Host remoteHost = new Host("remote-host");
        Inventory inventory = new Inventory(new Group("all", List.of(remoteHost), List.of(), Map.of()));

        // We set ansible_connection: local for the delegated host so DefaultConnectionFactory returns LocalConnection
        Task task = new Task("delegate task", "ping", Map.of("_raw_params", ""), Map.of("ansible_connection", "local"), null, null, null, List.of(), null, null, false,
                null, 3, 5, "localhost", false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("test play", "all", List.of(task));
        Playbook playbook = new Playbook(List.of(play));

        executor.execute(playbook, inventory);

        assertEquals(1, capturedConnections.size());
        assertTrue(capturedConnections.get(0) instanceof LocalConnection);
    }
}
