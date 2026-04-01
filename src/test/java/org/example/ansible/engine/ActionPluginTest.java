package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void testDebugActionPluginMsg() {
        Task task = new Task("Test Debug Msg", "debug", Map.of("msg", "Hello from Built-in Action Plugin"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), result.message());
        assertEquals("Hello from Built-in Action Plugin", result.data().get("msg"));
    }

    @Test
    void testDebugActionPluginVar() {
        variableManager.addFacts("localhost", Map.of("my_fact", "fact_value"));
        Task task = new Task("Test Debug Var", "debug", Map.of("var", "my_fact"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), result.message());
        assertEquals("fact_value", result.data().get("my_fact"));
    }

    @Test
    void testSetFactActionPlugin() {
        Task task = new Task("Test Set Fact", "set_fact", Map.of("new_fact", "new_value"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), result.message());

        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertEquals("new_value", facts.get("new_fact"));
    }

    @Test
    void testAssertActionPluginSuccess() {
        Task task = new Task("Test Assert Success", "assert", Map.of("that", List.of("1 == 1")));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), result.message());
    }

    @Test
    void testAssertActionPluginFailure() {
        Task task = new Task("Test Assert Failure", "assert", Map.of("that", List.of("1 == 2")));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertFalse(result.success());
        // assert module usually returns evaluated results in 'assertion' or similar
    }
}
