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

class TemplateIntegrationTest {

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
        play = new Play("Test Play", "all", List.of(), Map.of("user_name", "jules"), List.of(), List.of(), null, null, null, null, null, Map.of());

        // Mock 'file' module
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
    void testTemplateBasic() throws IOException {
        Path templateFile = tempDir.resolve("hello.j2");
        Files.writeString(templateFile, "Hello {{ user_name }}!");

        Path destFile = tempDir.resolve("hello.txt");

        Task task = new Task("Template Test", "template", Map.of(
                "src", "hello.j2",
                "dest", destFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertTrue(Files.exists(destFile));
        assertEquals("Hello jules!", Files.readString(destFile));
    }

    @Test
    void testTemplateWithControlStructures() throws IOException {
        Path templateFile = tempDir.resolve("loop.j2");
        Files.writeString(templateFile, "{% for i in range(3) %}{{ i }}{% endfor %}");

        Path destFile = tempDir.resolve("loop.txt");

        Task task = new Task("Template Loop Test", "template", Map.of(
                "src", "loop.j2",
                "dest", destFile.toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertEquals("012", Files.readString(destFile));
    }

    @Test
    void testTemplateWithAttributes() throws IOException {
        Path templateFile = tempDir.resolve("attr.j2");
        Files.writeString(templateFile, "content");

        Path destFile = tempDir.resolve("attr.txt");

        Task task = new Task("Template Attr Test", "template", Map.of(
                "src", "attr.j2",
                "dest", destFile.toString(),
                "mode", "0755",
                "owner", "admin"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success());
        assertEquals("0755", result.data().get("mode"));
        assertEquals("admin", result.data().get("owner"));
        assertTrue(Files.exists(destFile));
    }
}
