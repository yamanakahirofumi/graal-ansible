package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PythonModuleTest {

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testSimplePythonModule() {
        // Simple Python module that echoes back arguments and sets changed=True
        String script = """
            import json
            import sys
            import polyglot

            result = {
                "changed": True,
                "msg": "Hello from Python",
                "received_args": {k: complex_args[k] for k in complex_args}
            }
            sys.stdout.write(json.dumps(result))
            """;

        taskExecutor.registerModule("test_module", new PythonModule("test_module", script));
        Task task = new Task("test", "test_module", Map.of("foo", "bar"));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());
        assertEquals("OK", result.message());
        assertEquals("Hello from Python", result.data().get("msg"));

        @SuppressWarnings("unchecked")
        Map<String, Object> receivedArgs = (Map<String, Object>) result.data().get("received_args");
        assertEquals("bar", receivedArgs.get("foo"));
    }

    @Test
    void testPythonModuleFailure() {
        String script = """
            import json
            result = {
                "failed": True,
                "msg": "Something went wrong"
            }
            print(json.dumps(result))
            """;

        taskExecutor.registerModule("fail_module", new PythonModule("fail_module", script));
        Task task = new Task("test", "fail_module", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assertFalse(result.success());
        assertFalse(result.changed());
        assertEquals("Something went wrong", result.message());
    }
}
