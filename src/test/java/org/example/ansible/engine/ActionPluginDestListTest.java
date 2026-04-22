package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.example.ansible.module.python.PythonModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionPluginDestListTest {

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testCopyDestAsList(@TempDir Path tempDir) throws IOException {
        Path srcFile = tempDir.resolve("source.txt");
        Files.writeString(srcFile, "hello");
        Path destDir = tempDir.resolve("dest_dir");
        Files.createDirectories(destDir);

        // We simulate a case where dest becomes a list.
        // This often happens in our VariableResolver when a template resolves to a list.
        Task task = new Task("test copy", "copy", Map.of(
            "src", srcFile.toString(),
            "dest", "{{ target_path }}"
        ));

        Play play = new Play("test play", "all", List.of());
        Host host = new Host("localhost");

        Group all = new Group("all", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(all);

        VariableManager vm = new VariableManager(inventory, Map.of("target_path", List.of(destDir.toString() + "/")), tempDir);

        // We use a LocalConnection to make the test pass even without SSH
        TaskResult result = taskExecutor.execute(play, host, task, vm, false, null, Map.of(), null, null, new org.example.ansible.connection.LocalConnection(), null);

        // Operation should succeed because dest is now automatically flattened to a string in the action launcher
        assertTrue(result.success(), () -> "Copy failed: " + result.message() + ". Traceback: " + result.data().get("traceback"));
        assertTrue(Files.exists(destDir.resolve("source.txt")), "Dest file should exist");
    }
}
