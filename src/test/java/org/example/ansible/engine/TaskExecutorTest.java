package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Map;

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
        TaskResult result = executor.execute(task, BecomeContext.empty(), null);

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
        TaskResult result = executor.execute(task, BecomeContext.empty(), null);

        // Assert (検証)
        assertFalse(result.success());
        assertTrue(result.message().contains("Module not found"));
    }

    @Test
    void testIsActionPluginWithBuiltin(@TempDir Path tempDir) throws Exception {
        // Mock site-packages by setting system property
        Path sitePackages = tempDir.resolve("python-packages");
        Path actionPluginsDir = sitePackages.resolve("ansible/plugins/action");
        Files.createDirectories(actionPluginsDir);
        Files.createFile(actionPluginsDir.resolve("template.py"));

        String oldProp = System.getProperty("ansible.site.packages");
        System.setProperty("ansible.site.packages", sitePackages.toAbsolutePath().toString());
        System.setProperty("ansible.action_plugins.enabled", "true");

        try {
            // Use reflection to call private isActionPlugin
            Method method = TaskExecutor.class.getDeclaredMethod("isActionPlugin", String.class);
            method.setAccessible(true);

            assertTrue((Boolean) method.invoke(executor, "template"));
            assertFalse((Boolean) method.invoke(executor, "unknown_plugin"));
        } finally {
            System.clearProperty("ansible.action_plugins.enabled");
            if (oldProp != null) {
                System.setProperty("ansible.site.packages", oldProp);
            } else {
                System.getProperties().remove("ansible.site.packages");
            }
        }
    }
}
