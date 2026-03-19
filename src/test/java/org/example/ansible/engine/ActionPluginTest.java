package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionPluginTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @BeforeEach
    void setUp() {
        System.setProperty("ansible.action_plugins.enabled", "true");
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of());
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
        System.clearProperty("ansible.action_plugins.enabled");
    }

    @Test
    @Disabled("Action Plugin execution currently crashes due to core dependencies in GraalPy")
    void testDebugActionPluginDiscovery() {
        Task task = new Task("Test Debug", "debug", Map.of("msg", "Hello from Action Plugin"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);
        assertTrue(result.success(), "Result should be successful: " + (result != null ? result.message() : "null"));
    }
}
