package org.example.ansible.engine;

import org.example.ansible.inventory.IniInventoryParser;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.module.CommandModule;
import org.example.ansible.module.ShellModule;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleIntegrationTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        taskExecutor.registerModule("command", new CommandModule());
        taskExecutor.registerModule("shell", new ShellModule());
        taskExecutor.registerModule("debug", args -> {
            Object msg = args.getOrDefault("msg", "Hello world");
            return TaskResult.success(false, Map.of("msg", msg));
        });
        playbookExecutor = new PlaybookExecutor(taskExecutor);
    }

    @Test
    void testCommandModule() {
        String inventoryIni = "localhost";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test command
                  hosts: all
                  tasks:
                    - name: run echo
                      command: echo "hello world"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        TaskResult result = results.get("localhost").get(0);
        assertTrue(result.success());
        assertTrue(result.changed());
        assertEquals("hello world\n", result.data().get("stdout"));
        assertEquals(0, result.data().get("rc"));
    }

    @Test
    void testShellModule() {
        String inventoryIni = "localhost";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test shell
                  hosts: all
                  tasks:
                    - name: run shell pipe
                      shell: echo "hello" | tr 'a-z' 'A-Z'
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        TaskResult result = results.get("localhost").get(0);
        assertTrue(result.success());
        assertEquals("HELLO\n", result.data().get("stdout"));
    }

    @Test
    void testCommandFailure() {
        String inventoryIni = "localhost";
        Inventory inventory = new IniInventoryParser().parse(new ByteArrayInputStream(inventoryIni.getBytes(StandardCharsets.UTF_8)));

        String playbookYaml = """
                - name: test failure
                  hosts: all
                  tasks:
                    - name: fail command
                      command: /bin/false
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        TaskResult result = results.get("localhost").get(0);
        assertFalse(result.success());
        assertEquals(1, result.data().get("rc"));
    }
}
