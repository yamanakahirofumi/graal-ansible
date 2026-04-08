package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.VariableManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionModuleRuntimeTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;

    @BeforeEach
    void setUp() {
        variableManager = new VariableManager(null, Map.of());
        taskExecutor = new TaskExecutor();
        TaskExecutor.setCurrentVariableManager(variableManager);
    }

    @AfterEach
    void tearDown() {
        TaskExecutor.clearCurrentVariableManager();
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testSetFactModule() {
        Task task = new Task("test_set_fact", "set_fact", Map.of("my_var", "my_value"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), new LocalConnection(), Map.of());

        assertTrue(result.success(), "set_fact failed: " + result.message());

        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertEquals("my_value", facts.get("my_var"));
    }

    @Test
    void testFailModule() {
        Task task = new Task("test_fail", "fail", Map.of("msg", "Custom failure message"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), new LocalConnection(), Map.of());

        assertFalse(result.success());
        assertEquals("Custom failure message", result.data().get("msg"));
    }

    @Test
    void testGatherFactsModule() {
        Task task = new Task("test_gather_facts", "gather_facts", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), new LocalConnection(), Map.of());

        assertTrue(result.success(), "gather_facts failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertNotNull(facts.get("ansible_date_time"));
    }

    @Test
    void testDebugModule() {
        Task task = new Task("test_debug", "debug", Map.of("msg", "Hello Regression"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), new LocalConnection(), Map.of());

        assertTrue(result.success());
        assertEquals("Hello Regression", result.data().get("msg"));
    }

    @Test
    void testAssertModule() {
        Task task = new Task("test_assert", "assert", Map.of("that", List.of("1 == 1")));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), new LocalConnection(), Map.of());

        assertTrue(result.success(), "assert failed: " + result.data().toString());
        assertEquals(false, result.data().get("changed"));
    }
}
