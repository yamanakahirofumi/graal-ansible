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
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testLineInFilePresent() throws IOException {
        Path testFile = tempDir.resolve("test-line.txt");
        Files.writeString(testFile, "line1\nline2\n");

        Task task = new Task("Add line", "lineinfile", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "line", "line3"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        List<String> lines = Files.readAllLines(testFile);
        assertTrue(lines.contains("line3"));
        assertEquals(3, lines.size());
    }

    @Test
    void testLineInFileReplace() throws IOException {
        Path testFile = tempDir.resolve("test-replace.txt");
        Files.writeString(testFile, "line1\nline2\nline3\n");

        Task task = new Task("Replace line", "lineinfile", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "regexp", "^line2$",
                "line", "replaced-line2"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        List<String> lines = Files.readAllLines(testFile);
        assertFalse(lines.contains("line2"));
        assertTrue(lines.contains("replaced-line2"));
        assertEquals(3, lines.size());
    }

    @Test
    void testLineInFileAbsent() throws IOException {
        Path testFile = tempDir.resolve("test-absent.txt");
        Files.writeString(testFile, "line1\nline2\nline3\n");

        Task task = new Task("Remove line", "lineinfile", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "regexp", "^line2$",
                "state", "absent"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        List<String> lines = Files.readAllLines(testFile);
        assertFalse(lines.contains("line2"));
        assertEquals(2, lines.size());
    }

    @Test
    void testLineInFileCheckMode() throws IOException {
        Path testFile = tempDir.resolve("test-check.txt");
        Files.writeString(testFile, "line1\nline2\n");

        Task task = new Task("Add line check", "lineinfile", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "line", "line3"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, true, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed(), "Should indicate change in check mode");
        List<String> lines = Files.readAllLines(testFile);
        assertFalse(lines.contains("line3"), "File should NOT be changed in check mode");
        assertEquals(2, lines.size());
    }
}
