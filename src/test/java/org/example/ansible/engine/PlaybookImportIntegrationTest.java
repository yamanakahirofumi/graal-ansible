package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaybookImportIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testPlaybookImport() throws IOException {
        // 1. Setup imported playbook
        Path importedPlaybookPath = tempDir.resolve("imported.yml");
        Files.writeString(importedPlaybookPath, """
                - name: imported play
                  hosts: all
                  vars:
                    imported_var: original
                  tasks:
                    - name: imported task
                      debug:
                        msg: "imported_var is {{ imported_var }}"
                """);

        // 2. Setup main playbook
        Path mainPlaybookPath = tempDir.resolve("main.yml");
        Files.writeString(mainPlaybookPath, """
                - import_playbook: imported.yml
                  vars:
                    imported_var: overridden
                - name: main play
                  hosts: all
                  tasks:
                    - name: main task
                      debug:
                        msg: "hello from main"
                """);

        // 3. Setup inventory and executor
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        // Register debug manually for the mock execution
        taskExecutor.registerModule("debug", (args, become, context) -> {
            Object msg = args.get("msg");
            return TaskResult.success(false, Map.of("msg", msg));
        });

        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        // 4. Parse and Execute
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(mainPlaybookPath.toFile());

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, vm, false);

        // 5. Verify Results
        List<TaskResult> hostResults = results.get("localhost");
        // 1 from imported, 1 from main
        assertEquals(2, hostResults.size(), "Should have 2 task results");

        assertEquals("imported_var is overridden", hostResults.get(0).data().get("msg"));
        assertEquals("hello from main", hostResults.get(1).data().get("msg"));

        taskExecutor.close();
    }
}
