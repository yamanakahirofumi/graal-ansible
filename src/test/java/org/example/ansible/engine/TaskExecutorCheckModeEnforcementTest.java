package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.python.PythonModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorCheckModeEnforcementTest {

    private TaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void testCheckModeIsEnforcedToFalseInPythonModule() {
        // Arrange
        // We use a script that echoes back whether _ansible_check_mode was true or false
        String script = """
            import json
            import sys
            result = {
                "changed": False,
                "check_mode_value": complex_args.get('_ansible_check_mode')
            }
            sys.stdout.write(json.dumps(result))
            """;

        executor.registerModule("test_module", new PythonModule("test_module", script));

        // Arguments that include _ansible_check_mode: true
        Map<String, Object> args = new HashMap<>();
        args.put("_ansible_check_mode", true);

        Task task = new Task("test task", "test_module", args);

        // Act
        TaskResult result = executor.execute(task, BecomeContext.empty());

        // Assert
        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals(false, result.data().get("check_mode_value"),
            "Python module should have seen _ansible_check_mode as false");
    }
}
