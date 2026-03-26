package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LineInFileIntegrationTest {

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
        variableManager = new VariableManager(inventory, Map.of());
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testLineInFilePresent() throws IOException {
        Path testFile = tempDir.resolve("lineinfile-test.txt");
        Files.writeString(testFile, "initial line\n");

        Task task = new Task("Add line", "lineinfile", Map.of(
                "path", testFile.toString(),
                "line", "new line added"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed(), "Should be changed");

        String content = Files.readString(testFile);
        assertTrue(content.contains("new line added"), "Content should contain the new line");
        assertTrue(content.contains("initial line"), "Content should still contain the initial line");
    }

    @Test
    void testLineInFileAbsent() throws IOException {
        Path testFile = tempDir.resolve("lineinfile-absent.txt");
        Files.writeString(testFile, "initial line\nline to remove\nlast line\n");

        Task task = new Task("Remove line", "lineinfile", Map.of(
                "path", testFile.toString(),
                "line", "line to remove",
                "state", "absent"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed(), "Should be changed");

        String content = Files.readString(testFile);
        assertFalse(content.contains("line to remove"), "Content should not contain the removed line");
        assertTrue(content.contains("initial line"), "Content should still contain the initial line");
    }
}
