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

class UnicodeIntegrationTest {

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
        play = new Play("Unicode Play", "all", List.of(), Map.of("jp_msg", "こんにちは"), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testUnicodeDebug() {
        Task task = new Task("Debug Japanese", "debug", Map.of("msg", "{{ jp_msg }}"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertEquals("こんにちは", result.data().get("msg"));
    }

    @Test
    void testUnicodeCommand() {
        // Skip on Windows if echo doesn't handle UTF-8 well by default in this environment
        if (System.getProperty("os.name").toLowerCase().contains("win")) return;

        Task task = new Task("Echo Japanese", "command", Map.of("_raw_params", "echo こんにちは"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertEquals("こんにちは", ((String)result.data().get("stdout")).trim());
    }

    @Test
    void testUnicodeFile() throws IOException {
        Path jpFile = tempDir.resolve("日本語.txt");
        Task task = new Task("Create Japanese file", "file", Map.of(
                "path", jpFile.toString(),
                "state", "touch"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(jpFile));

        Task taskRemove = new Task("Remove Japanese file", "file", Map.of(
                "path", jpFile.toString(),
                "state", "absent"
        ));
        TaskResult resultRemove = taskExecutor.execute(play, host, taskRemove, variableManager, false, null, new LocalConnection(), null);
        assertTrue(resultRemove.success());
        assertFalse(Files.exists(jpFile));
    }

    @Test
    void testUnicodeTemplate() throws IOException {
        Path templateFile = tempDir.resolve("unicode.j2");
        Files.writeString(templateFile, "メッセージ: {{ jp_msg }}");

        Path destFile = tempDir.resolve("unicode.txt");
        Task task = new Task("Template Japanese", "template", Map.of(
                "src", "unicode.j2",
                "dest", destFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertEquals("メッセージ: こんにちは", Files.readString(destFile).trim());
    }
}
