package org.example.ansible.module.python;

import org.example.ansible.engine.Playbook;
import org.example.ansible.engine.PlaybookExecutor;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.module.StandardModules;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActualModuleIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        StandardModules.registerAll(taskExecutor);
        playbookExecutor = new PlaybookExecutor(taskExecutor);
    }

    @Test
    void testCopyAndFileModules() throws IOException {
        Path srcFile = tempDir.resolve("src.txt");
        Files.writeString(srcFile, "source content");
        Path destFile = tempDir.resolve("integrated_copy.txt");
        Path testDir = tempDir.resolve("test_subdir");

        String playbookYaml = """
                - name: integration test
                  hosts: localhost
                  tasks:
                    - name: create directory
                      file:
                        path: "%s"
                        state: directory
                    - name: copy file
                      copy:
                        src: "%s"
                        dest: "%s"
                """.formatted(testDir.toString().replace("\\", "/"),
                              srcFile.toString().replace("\\", "/"),
                              destFile.toString().replace("\\", "/"));

        String inventoryIni = "localhost ansible_connection=local";

        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults, "Should have results for localhost");
        if (hostResults.size() < 2) {
            for (TaskResult r : hostResults) {
                System.out.println("Result: " + r);
            }
        }
        assertEquals(2, hostResults.size(), "Should have executed 2 tasks");

        assertTrue(hostResults.get(0).success(), "File task failed: " + hostResults.get(0).message() + " Data: " + hostResults.get(0).data());
        assertTrue(Files.isDirectory(testDir), "Created directory should exist");

        assertTrue(hostResults.get(1).success(), "Copy task failed: " + hostResults.get(1).message());
        assertTrue(Files.exists(destFile), "Copied file should exist");
        assertEquals("source content", Files.readString(destFile));
    }
}
