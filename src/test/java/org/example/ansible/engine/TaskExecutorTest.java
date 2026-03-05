package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorTest {

    private TaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TaskExecutor();
    }

    @Test
    void testExecuteDebugTask() {
        // Arrange (準備)
        executor.registerModule("debug", (args, becomeContext) -> {
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
}
