package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CopyIntegrationTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        // Initialize VariableManager with baseDir pointing to tempDir
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());

        // Mock 'file' module because CopyAction calls it to set attributes
        taskExecutor.registerModule("file", (args, becomeContext, context) -> {
            Map<String, Object> data = new HashMap<>(args);
            data.put("changed", true);
            return TaskResult.success(data);
        });
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testCopyWithContent() throws IOException {
        Path destFile = tempDir.resolve("dest-content.txt");
        String content = "Hello from CopyAction content";

        Task task = new Task("Copy Content", "copy", Map.of(
                "content", content,
                "dest", destFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertTrue(result.changed());
        assertTrue(Files.exists(destFile));
        assertEquals(content, Files.readString(destFile));
    }

    @Test
    void testCopyWithSrc() throws IOException {
        Path srcFile = tempDir.resolve("src-file.txt");
        String content = "Hello from CopyAction src";
        Files.writeString(srcFile, content);

        Path destFile = tempDir.resolve("dest-src.txt");

        // Use relative path for src, VariableManager's baseDir is tempDir
        Task task = new Task("Copy Src", "copy", Map.of(
                "src", "src-file.txt",
                "dest", destFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertTrue(Files.exists(destFile));
        assertEquals(content, Files.readString(destFile));
    }

    @Test
    void testCopyWithAttributes() throws IOException {
        Path destFile = tempDir.resolve("dest-attr.txt");
        String content = "Hello from CopyAction attributes";

        Task task = new Task("Copy Content with Attrs", "copy", Map.of(
                "content", content,
                "dest", destFile.toString(),
                "mode", "0644",
                "owner", "testuser"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals("0644", result.data().get("mode"));
        assertEquals("testuser", result.data().get("owner"));
        assertTrue(Files.exists(destFile));
    }
}
