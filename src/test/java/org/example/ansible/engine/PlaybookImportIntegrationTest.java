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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void testPlaybookImportWithTags() throws IOException {
        // 1. Setup imported playbook
        Path importedPlaybookPath = tempDir.resolve("imported_tags.yml");
        Files.writeString(importedPlaybookPath, """
                - name: imported play
                  hosts: all
                  tasks:
                    - name: task with tag
                      debug:
                        msg: "with tag"
                      tags: [inner_tag]
                    - name: task without tag
                      debug:
                        msg: "without tag"
                """);

        // 2. Setup main playbook with tags on import
        Path mainPlaybookPath = tempDir.resolve("main_tags.yml");
        Files.writeString(mainPlaybookPath, """
                - import_playbook: imported_tags.yml
                  tags: [outer_tag]
                """);

        // 3. Setup inventory and executor
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        taskExecutor.registerModule("debug", (args, become, context) -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        // 4. Parse
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(mainPlaybookPath.toFile());

        // 5. Execute with --tags outer_tag
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, vm, false, List.of("outer_tag"), List.of(), null);

        // All tasks in imported playbook should have inherited outer_tag
        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());
        assertFalse(hostResults.get(0).isSkipped());
        assertFalse(hostResults.get(1).isSkipped());

        // 6. Execute with --tags inner_tag
        results.clear();
        results = playbookExecutor.execute(playbook, inventory, vm, false, List.of("inner_tag"), List.of(), null);
        hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());
        assertFalse(hostResults.get(0).isSkipped(), "Task with inner_tag should run");
        assertTrue(hostResults.get(1).isSkipped(), "Task without inner_tag should be skipped");

        taskExecutor.close();
    }

    @Test
    void testNestedPlaybookImportWithTagsAndVars() throws IOException {
        // 1. Setup leaf playbook
        Path leafPath = tempDir.resolve("leaf.yml");
        Files.writeString(leafPath, """
                - name: leaf play
                  hosts: all
                  tasks:
                    - name: leaf task
                      debug:
                        msg: "var={{ nested_var }} tags={{ ansible_run_tags | default([]) }}"
                """);

        // 2. Setup middle playbook
        Path middlePath = tempDir.resolve("middle.yml");
        Files.writeString(middlePath, """
                - import_playbook: leaf.yml
                  vars:
                    nested_var: middle_val
                  tags: [middle_tag]
                """);

        // 3. Setup root playbook
        Path rootPath = tempDir.resolve("root.yml");
        Files.writeString(rootPath, """
                - import_playbook: middle.yml
                  vars:
                    nested_var: root_val
                  tags: [root_tag]
                """);

        // 4. Setup inventory and executor
        Host host = new Host("localhost");
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));

        TaskExecutor taskExecutor = new TaskExecutor();
        taskExecutor.registerModule("debug", (args, become, context) -> TaskResult.success(false, Map.of("msg", args.get("msg"))));

        PlaybookExecutor playbookExecutor = new PlaybookExecutor(taskExecutor, (h, vars) -> new org.example.ansible.connection.LocalConnection());
        Map<String, Object> cliVars = new HashMap<>();
        cliVars.put("ansible_run_tags", List.of("root_tag"));
        VariableManager vm = new VariableManager(inventory, cliVars, Map.of(), tempDir, null);

        // 5. Parse
        YamlParser parser = new YamlParser();
        Playbook playbook = parser.parse(rootPath.toFile());

        // 6. Execute with tags filtering to verify tags propagation
        // Note: ansible_run_tags is not automatically set in our mock debug, but we can verify execution
        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory, vm, false, List.of("root_tag"), List.of(), null);

        List<TaskResult> hostResults = results.get("localhost");
        assertNotNull(hostResults);
        assertEquals(1, hostResults.size());
        assertFalse(hostResults.get(0).isSkipped());
        // Precedence: middle_val should win because it's "closer" to the play in the import chain?
        // Actually, Ansible import_playbook vars merge down.
        // If leaf.yml has no vars, middle.yml adds nested_var=middle_val.
        // Then root.yml imports middle.yml and adds nested_var=root_val.
        // In handleImportPlaybook, combinedVars = inherited (root) + current (middle).
        // So middle_val should win over root_val.
        assertEquals("var=middle_val tags=[root_tag]", hostResults.get(0).data().get("msg"));

        // Verify middle_tag also works
        results.clear();
        // Update vm with new run tags for this execution
        Map<String, Object> cliVars2 = new HashMap<>();
        cliVars2.put("ansible_run_tags", List.of("middle_tag"));
        VariableManager vm2 = new VariableManager(inventory, cliVars2, Map.of(), tempDir, null);
        results = playbookExecutor.execute(playbook, inventory, vm2, false, List.of("middle_tag"), List.of(), null);
        assertFalse(results.get("localhost").get(0).isSkipped());

        taskExecutor.close();
    }
}
