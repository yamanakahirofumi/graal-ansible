package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
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

class IncludeTasksIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;
    private Inventory inventory;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());
        inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testIncludeTasksBasic() throws IOException {
        // Arrange
        Path includedFile = tempDir.resolve("tasks.yml");
        String tasksYaml = """
                - name: task from file
                  debug:
                    msg: "from file"
                """;
        Files.writeString(includedFile, tasksYaml);

        String playbookYaml = """
                - name: test include_tasks
                  hosts: all
                  tasks:
                    - name: include it
                      include_tasks: tasks.yml
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, false);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        // Result 0: include_tasks (doesn't produce a TaskResult directly unless it fails)
        // Actually, executeIncludeTasks doesn't add a result for the inclusion task itself unless it skips or fails.
        // The child tasks add their results.
        assertEquals(1, hostResults.size());
        assertEquals("from file", hostResults.get(0).data().get("msg"));
    }

    @Test
    void testIncludeTasksWithWhen() throws IOException {
        // Arrange
        Path includedFile = tempDir.resolve("tasks.yml");
        Files.writeString(includedFile, "- debug: { msg: 'run' }");

        String playbookYaml = """
                - name: test include_tasks when
                  hosts: all
                  vars:
                    run_it: false
                  tasks:
                    - name: include it
                      include_tasks: tasks.yml
                      when: run_it
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, false);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertTrue(Boolean.TRUE.equals(hostResults.get(0).data().get("skipped")));
    }

    @Test
    void testIncludeTasksWithLoop() throws IOException {
        // Arrange
        Path includedFile = tempDir.resolve("tasks.yml");
        Files.writeString(includedFile, "- debug: { msg: 'item is {{ item }}' }");

        String playbookYaml = """
                - name: test include_tasks loop
                  hosts: all
                  tasks:
                    - name: include loop
                      include_tasks: tasks.yml
                      loop: [ 'a', 'b' ]
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, false);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());
        assertEquals("item is a", hostResults.get(0).data().get("msg"));
        assertEquals("item is b", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testIncludeTasksWithVars() throws IOException {
        // Arrange
        Path includedFile = tempDir.resolve("tasks.yml");
        Files.writeString(includedFile, "- debug: { msg: 'val is {{ my_var }}' }");

        String playbookYaml = """
                - name: test include_tasks vars
                  hosts: all
                  tasks:
                    - name: include with vars
                      include_tasks: tasks.yml
                      vars:
                        my_var: "inner"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, false);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertEquals("val is inner", hostResults.get(0).data().get("msg"));
    }

    @Test
    void testImportTasksBasic() throws IOException {
        // Arrange
        Path includedFile = tempDir.resolve("tasks.yml");
        Files.writeString(includedFile, "- debug: { msg: 'imported' }");

        String playbookYaml = """
                - name: test import_tasks
                  hosts: all
                  tasks:
                    - name: import it
                      import_tasks: tasks.yml
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        // Act
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir, false);

        // Assert
        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertEquals("imported", hostResults.get(0).data().get("msg"));
    }
}
