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

class ReplaceIntegrationTest {

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
    void testReplaceString() throws IOException {
        Path testFile = tempDir.resolve("test-replace.txt");
        Files.writeString(testFile, "Hello World\nHello Ansible\n");

        Task task = new Task("Replace string", "replace", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "regexp", "Hello",
                "replace", "Hi"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        String content = Files.readString(testFile);
        assertEquals("Hi World\nHi Ansible\n", content);
    }

    @Test
    void testReplaceWithRegexp() throws IOException {
        Path testFile = tempDir.resolve("test-regexp.txt");
        Files.writeString(testFile, "old_value = 1\nold_value = 2\n");

        Task task = new Task("Replace with regexp", "replace", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "regexp", "old_value = (\\d+)",
                "replace", "new_value = \\1"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        String content = Files.readString(testFile);
        assertEquals("new_value = 1\nnew_value = 2\n", content);
    }

    @Test
    void testReplaceNoMatch() throws IOException {
        Path testFile = tempDir.resolve("test-nomatch.txt");
        Files.writeString(testFile, "Hello World\n");

        Task task = new Task("Replace nomatch", "replace", Map.of(
                "path", testFile.toAbsolutePath().toString(),
                "regexp", "Goodbye",
                "replace", "Hi"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertFalse(result.changed());
        String content = Files.readString(testFile);
        assertEquals("Hello World\n", content);
    }
}
