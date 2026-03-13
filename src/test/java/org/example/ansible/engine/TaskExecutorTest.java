package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorTest {

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
    void testExecuteDebugTask() {
        // Arrange (準備)
        executor.registerModule("debug", (args, becomeContext, context) -> {
            String msg = (String) args.getOrDefault("msg", "");
            return TaskResult.success(false, Map.of("msg", msg));
        });
        Task task = new Task("test debug", "debug", Map.of("msg", "hello world"));

        // Act (実行)
        TaskResult result = executor.execute(task, BecomeContext.empty());

        // Assert (検証)
        assertTrue(result.success());
        assertFalse(result.changed());
        assertEquals("hello world", result.data().get("msg"));
    }

    @Test
    void testExecuteModuleNotFound() {
        // Arrange (準備)
        Task task = new Task("test unknown", "unknown", Map.of());

        // Act (実行)
        TaskResult result = executor.execute(task, BecomeContext.empty());

        // Assert (検証)
        assertFalse(result.success());
        assertTrue(result.message().contains("Module not found"));
    }

    @Test
    void testCheckModeEnforcement() {
        // Arrange
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();
        executor.registerModule("test_module", (args, becomeContext, context) -> {
            capturedArgs.set(new HashMap<>(args));
            return TaskResult.success(Map.of());
        });

        // Arguments that include _ansible_check_mode: true
        Map<String, Object> args = new HashMap<>();
        args.put("_ansible_check_mode", true);

        Task task = new Task("test task", "test_module", args);

        // Act
        executor.execute(task, BecomeContext.empty());

        // Assert
        assertNotNull(capturedArgs.get());
        assertEquals(false, capturedArgs.get().get("_ansible_check_mode"),
            "TaskExecutor should have overridden _ansible_check_mode to false");
    }
}
