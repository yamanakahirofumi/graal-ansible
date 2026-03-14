package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPluginIntegrationTest {

    private TaskExecutor taskExecutor;
    private LocalConnection connection;

    @TempDir
    Path tempDir;

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
    void testTemplateActionPlugin() throws IOException {
        taskExecutor.registerModule("template", new PythonModule("template"));
        taskExecutor.registerModule("copy", new PythonModule("copy"));

        Path localTemplateFile = tempDir.resolve("test.j2");
        Files.writeString(localTemplateFile, "Hello {{ name }}!");

        String destPath = tempDir.resolve("output.txt").toString();

        // We need to pass 'name' variable.
        taskExecutor.setCurrentTaskVars(Map.of("name", "world"));

        Task task = new Task("test_template", "template", Map.of(
                "src", localTemplateFile.toString(),
                "dest", destPath
        ));

        // This is currently failing due to GraalPy process execution issues
        // but the infrastructure is there.
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection);

        assertTrue(result.success(), "Execution failed: " + result.message());

        assertTrue(Files.exists(Path.of(destPath)));
        assertEquals("Hello world!", Files.readString(Path.of(destPath)).trim());
    }
}
