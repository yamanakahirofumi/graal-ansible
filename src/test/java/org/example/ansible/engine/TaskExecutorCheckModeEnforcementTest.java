package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    void testCheckModeIsEnforcedToFalse() {
        // Arrange
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();
        executor.registerModule("test_module", (args, becomeContext, context) -> {
            capturedArgs.set(new HashMap<>(args));
            return TaskResult.success(Map.of());
        });

        // Arguments that include _ansible_check_mode: true
        Map<String, Object> args = new HashMap<>();
        args.put("param1", "value1");
        args.put("_ansible_check_mode", true);

        Task task = new Task("test task", "test_module", args);

        // Act
        executor.execute(task, BecomeContext.empty());

        // Assert
        assertNotNull(capturedArgs.get());
        assertEquals("value1", capturedArgs.get().get("param1"));
        assertEquals(false, capturedArgs.get().get("_ansible_check_mode"),
            "TaskExecutor should have overridden _ansible_check_mode to false");
    }
}
