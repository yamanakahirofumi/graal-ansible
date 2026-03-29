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
        play = new Play("Unicode Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testUnicodeFilename() throws IOException {
        String unicodeName = "日本語ファイル.txt";
        Path targetFile = tempDir.resolve(unicodeName);

        Task task = new Task("Touch unicode file", "file", Map.of(
                "path", targetFile.toString(),
                "state", "touch"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), "Failed to create unicode file: " + result.message());
        assertTrue(Files.exists(targetFile), "File does not exist: " + targetFile);
    }

    @Test
    void testUnicodeContent() throws IOException {
        Path targetFile = tempDir.resolve("content-test.txt");
        String content = "こんにちは、世界！\nUTF-8 content test.";

        Task task = new Task("Write unicode content", "copy", Map.of(
                "dest", targetFile.toString(),
                "content", content
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), "Failed to write unicode content: " + result.message());
        assertEquals(content, Files.readString(targetFile).trim());
    }

    @Test
    void testUnicodeVariables() {
        Map<String, Object> vars = Map.of("unicode_var", "プログラミング");
        String template = "Value: {{ unicode_var }}";

        Task task = new Task("Debug unicode var", "debug", Map.of(
                "msg", template
        ));

        // Use a new VariableManager with the unicode variable
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        VariableManager vmWithVars = new VariableManager(inventory, vars);

        TaskResult result = taskExecutor.execute(play, host, task, vmWithVars, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertEquals("Value: プログラミング", result.data().get("msg"));
    }

    @Test
    void testUnicodeInRegister() throws IOException {
        Path targetFile = tempDir.resolve("register-test.txt");
        String content = "登録テストデータ";
        Files.writeString(targetFile, content);

        Task task = new Task("Slurp unicode file", "slurp", Map.of(
                "src", targetFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        // Results are base64 encoded by slurp, but let's see if we can use it in a subsequent task via debug
        String encoded = (String) result.data().get("content");
        assertNotNull(encoded);

        // Simulate registering and using the variable
        variableManager.registerVariable(host.name(), "slurped_data", result.data());
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task);

        Task taskDebug = new Task("Debug slurped content", "debug", Map.of(
                "msg", "{{ slurped_data.content }}"
        ));
        TaskResult debugResult = taskExecutor.execute(play, host, taskDebug, variableManager, false, null, new LocalConnection(), null);

        assertTrue(debugResult.success());
        assertEquals(encoded, debugResult.data().get("msg"));
    }
}
