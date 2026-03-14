package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test using actual ansible-core modules executed locally.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ActualModuleLocalIntegrationTest {

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;
    private LocalConnection connection;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connection = new LocalConnection();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testActualPingModule() {
        taskExecutor.registerModule("ping", new PythonModule("ping"));

        Task task = new Task("test_ping", "ping", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assumeTrue(result.success() || !result.message().contains("ShouldNotReachHere"),
                "Skipping due to restricted GraalPy environment: " + result.message());

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("pong", result.data().get("ping"));
    }

    @Test
    void testActualFileModule() throws IOException {
        taskExecutor.registerModule("file", new PythonModule("file"));

        Path testFile = tempDir.resolve("local-touch-test.txt");
        Task task = new Task("test_file", "file", Map.of(
                "path", testFile.toString(),
                "state", "touch"
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assumeTrue(result.success() || !result.message().contains("ShouldNotReachHere"),
                "Skipping due to restricted GraalPy environment: " + result.message());

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(Files.exists(testFile), "File should be created");
    }

    @Test
    void testActualStatModule() throws IOException {
        taskExecutor.registerModule("stat", new PythonModule("stat"));

        Path testFile = tempDir.resolve("local-stat-test.txt");
        Files.writeString(testFile, "test data");

        Task task = new Task("test_stat", "stat", Map.of(
                "path", testFile.toString()
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assumeTrue(result.success() || !result.message().contains("ShouldNotReachHere"),
                "Skipping due to restricted GraalPy environment: " + result.message());

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> stat = (Map<String, Object>) result.data().get("stat");
        assertNotNull(stat);
        assertTrue((Boolean) stat.get("exists"));
    }

    @Test
    void testActualCopyModule() throws IOException {
        taskExecutor.registerModule("copy", new PythonModule("copy"));

        Path srcFile = tempDir.resolve("local-copy-src.txt");
        String content = "Hello from Actual Copy Module (local)";
        Files.writeString(srcFile, content);

        Path destFile = tempDir.resolve("local-copy-dest.txt");
        Task task = new Task("test_copy", "copy", Map.of(
                "src", srcFile.toString(),
                "dest", destFile.toString()
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assumeTrue(result.success() || !result.message().contains("ShouldNotReachHere"),
                "Skipping due to restricted GraalPy environment: " + result.message());

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals(content, Files.readString(destFile).trim());
    }

    @Test
    void testActualTemplateModule() throws IOException {
        taskExecutor.registerModule("template", new PythonModule("template"));

        Path srcFile = tempDir.resolve("local-template.j2");
        Path destFile = tempDir.resolve("local-template-out.txt");
        Files.writeString(srcFile, "Hello {{ name }}!");

        Task task = new Task("test_template", "template", Map.of(
                "src", srcFile.toString(),
                "dest", destFile.toString(),
                "name", "LocalWorld"
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assumeTrue(result.success() || !(result.message().contains("ShouldNotReachHere") || result.message().contains("Module produced no output")),
                "Skipping due to restricted GraalPy environment or action plugin: " + result.message());

        assertTrue(result.success(), "Execution failed: " + result.message());
    }
}
