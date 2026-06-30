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

class CollectionIntegrationTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of());
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testCollectionActionPlugin(@TempDir Path tempDir) throws IOException {
        // Arrange: Create a mock collection structure
        // <tempDir>/ansible_collections/test_ns/test_coll/plugins/action/test_action.py
        Path actionDir = tempDir.resolve("ansible_collections/test_ns/test_coll/plugins/action");
        Files.createDirectories(actionDir);

        String actionCode =
            "from ansible.plugins.action import ActionBase\n" +
            "class ActionModule(ActionBase):\n" +
            "    def run(self, tmp=None, task_vars=None):\n" +
            "        res = super(ActionModule, self).run(tmp, task_vars)\n" +
            "        res['msg'] = 'Hello from collection action plugin'\n" +
            "        res['changed'] = True\n" +
            "        return res\n";
        Files.writeString(actionDir.resolve("test_action.py"), actionCode);

        // Act: Set collection paths and execute task
        taskExecutor.setCollectionPaths(List.of(tempDir.toAbsolutePath().toString()));
        Task task = new Task("test_action", "test_ns.test_coll.test_action", Map.of());

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        // Assert
        assertTrue(result.success(), "Action plugin execution failed: " + result.message());
        assertTrue(result.changed());
        assertEquals("Hello from collection action plugin", result.data().get("msg"));
    }

    @Test
    void testCollectionModule(@TempDir Path tempDir) throws IOException {
        // Arrange: Create a mock collection structure
        // <tempDir>/ansible_collections/test_ns/test_coll/plugins/modules/test_mod.py
        Path moduleDir = tempDir.resolve("ansible_collections/test_ns/test_coll/plugins/modules");
        Files.createDirectories(moduleDir);

        String moduleCode =
            "#!/usr/bin/python\n" +
            "from ansible.module_utils.basic import AnsibleModule\n" +
            "def main():\n" +
            "    module = AnsibleModule(argument_spec=dict(name=dict(type='str', required=True)))\n" +
            "    module.exit_json(changed=True, message='Hello ' + module.params['name'])\n" +
            "if __name__ == '__main__':\n" +
            "    main()\n";
        Files.writeString(moduleDir.resolve("test_mod.py"), moduleCode);

        // Act: Set collection paths and execute task
        taskExecutor.setCollectionPaths(List.of(tempDir.toAbsolutePath().toString()));
        Task task = new Task("test_mod", "test_ns.test_coll.test_mod", Map.of("name", "World"));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        // Assert
        assertTrue(result.success(), "Module execution failed: " + result.message());
        assertTrue(result.changed());
        assertEquals("Hello World", result.data().get("message"));
    }
}
