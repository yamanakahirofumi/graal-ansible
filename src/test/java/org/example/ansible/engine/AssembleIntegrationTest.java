package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssembleIntegrationTest {

    @TempDir
    Path tempDir;

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
    void testAssembleModule() throws IOException {
        Path srcDir = tempDir.resolve("fragments");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("01_file.txt"), "fragment1");
        Files.writeString(srcDir.resolve("02_file.txt"), "fragment2");

        Path destFile = tempDir.resolve("assembled.txt");

        Task task = new Task("test_assemble", "assemble", Map.of(
                "src", srcDir.toString(),
                "dest", destFile.toString(),
                "remote_src", false
        ));

        // Use LocalConnection to verify logic without Docker
        LocalConnection connection = new LocalConnection();

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed(), "File should have been assembled");
        assertTrue(Files.exists(destFile), "Destination file should exist");

        String content = Files.readString(destFile);
        // Default delimiter is None, but assemble adds newline between fragments if missing
        assertEquals("fragment1\nfragment2", content.trim());
    }

    @Test
    void testAssembleWithDelimiter() throws IOException {
        Path srcDir = tempDir.resolve("fragments_delim");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("a.txt"), "AAA");
        Files.writeString(srcDir.resolve("b.txt"), "BBB");

        Path destFile = tempDir.resolve("assembled_delim.txt");

        Task task = new Task("test_assemble_delim", "assemble", Map.of(
                "src", srcDir.toString(),
                "dest", destFile.toString(),
                "delimiter", "---",
                "remote_src", false
        ));

        LocalConnection connection = new LocalConnection();
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success());
        String content = Files.readString(destFile);
        // AAA -> \n -> --- -> \n -> BBB
        assertTrue(content.contains("AAA"));
        assertTrue(content.contains("---"));
        assertTrue(content.contains("BBB"));
    }
}
