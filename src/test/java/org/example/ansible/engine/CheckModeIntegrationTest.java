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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CheckModeIntegrationTest {

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
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Check Mode Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testFileCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("check-file.txt");

        // 1. touch in check mode
        Task task = new Task("Touch in check mode", "file", Map.of(
                "path", targetFile.toString(),
                "state", "touch"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: yes

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed(), "Should report changed=true in check mode");
        assertFalse(Files.exists(targetFile), "File should NOT be created in check mode");
    }

    @Test
    void testLineInFileCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("check-line.txt");
        Files.writeString(targetFile, "line 1\n");

        Task task = new Task("Add line in check mode", "lineinfile", Map.of(
                "path", targetFile.toString(),
                "line", "line 2"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: yes

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertEquals("line 1\n", Files.readString(targetFile));
    }

    @Test
    void testReplaceCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("check-replace.txt");
        Files.writeString(targetFile, "hello world");

        Task task = new Task("Replace in check mode", "replace", Map.of(
                "path", targetFile.toString(),
                "regexp", "world",
                "replace", "ansible"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 0, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: yes

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertEquals("hello world", Files.readString(targetFile));
    }
}
