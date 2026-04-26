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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookImportIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;
    private Inventory inventory;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());
        Host host = new Host("localhost");
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testBasicImportPlaybook() throws IOException {
        Path importedPlaybook = tempDir.resolve("imported.yml");
        Files.writeString(importedPlaybook, """
                - name: Imported Play
                  hosts: all
                  tasks:
                    - name: ping task
                      ping:
                """);

        Path mainPlaybook = tempDir.resolve("main.yml");
        Files.writeString(mainPlaybook, """
                - import_playbook: imported.yml
                - name: Main Play
                  hosts: all
                  tasks:
                    - name: debug task
                      debug:
                        msg: "hello from main"
                """);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(mainPlaybook.toFile());

        assertEquals(2, playbook.plays().size());
        assertEquals("Imported Play", playbook.plays().get(0).name());
        assertEquals("Main Play", playbook.plays().get(1).name());

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir);
        List<TaskResult> hostResults = results.get("localhost");

        assertEquals(2, hostResults.size());
        assertTrue(hostResults.get(0).success());
        assertEquals("pong", hostResults.get(0).data().get("ping"));
        assertTrue(hostResults.get(1).success());
        assertEquals("hello from main", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testImportPlaybookWithVars() throws IOException {
        Path importedPlaybook = tempDir.resolve("imported_vars.yml");
        Files.writeString(importedPlaybook, """
                - name: Imported Play with Vars
                  hosts: all
                  tasks:
                    - name: debug task
                      debug:
                        msg: "val is {{ my_var }}"
                """);

        Path mainPlaybook = tempDir.resolve("main_vars.yml");
        Files.writeString(mainPlaybook, """
                - import_playbook: imported_vars.yml
                  vars:
                    my_var: "imported_value"
                """);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(mainPlaybook.toFile());

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir);
        List<TaskResult> hostResults = results.get("localhost");

        assertEquals(1, hostResults.size());
        assertTrue(hostResults.get(0).success());
        assertEquals("val is imported_value", hostResults.get(0).data().get("msg"));
    }

    @Test
    void testRecursiveImportPlaybook() throws IOException {
        Path playbookC = tempDir.resolve("playbook_c.yml");
        Files.writeString(playbookC, """
                - name: Play C
                  hosts: all
                  tasks:
                    - debug: { msg: "from C" }
                """);

        Path playbookB = tempDir.resolve("playbook_b.yml");
        Files.writeString(playbookB, """
                - import_playbook: playbook_c.yml
                - name: Play B
                  hosts: all
                  tasks:
                    - debug: { msg: "from B" }
                """);

        Path playbookA = tempDir.resolve("playbook_a.yml");
        Files.writeString(playbookA, """
                - import_playbook: playbook_b.yml
                """);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(playbookA.toFile());

        assertEquals(2, playbook.plays().size());
        assertEquals("Play C", playbook.plays().get(0).name());
        assertEquals("Play B", playbook.plays().get(1).name());

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir);
        List<TaskResult> hostResults = results.get("localhost");

        assertEquals(2, hostResults.size());
        assertEquals("from C", hostResults.get(0).data().get("msg"));
        assertEquals("from B", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testRelativeImportInSubdir() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);

        Path importedPlaybook = subDir.resolve("imported.yml");
        Files.writeString(importedPlaybook, """
                - name: Subdir Play
                  hosts: all
                  tasks:
                    - debug: { msg: "from subdir" }
                """);

        Path mainPlaybook = tempDir.resolve("main.yml");
        Files.writeString(mainPlaybook, """
                - import_playbook: subdir/imported.yml
                """);

        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(mainPlaybook.toFile());

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, Map.of(), tempDir);
        List<TaskResult> hostResults = results.get("localhost");

        assertEquals(1, hostResults.size());
        assertEquals("from subdir", hostResults.get(0).data().get("msg"));
    }
}
