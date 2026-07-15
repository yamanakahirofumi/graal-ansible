package org.example.ansible.inventory;

import org.example.ansible.util.PythonEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PythonInventoryPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void testPythonInventoryProvider_CustomPlugin() throws IOException {
        // 1. Create a mock Python inventory plugin
        Path collectionDir = tempDir.resolve("ansible_collections/test_ns/test_coll/plugins/inventory");
        Files.createDirectories(collectionDir);

        String pythonPlugin =
                "from ansible.plugins.inventory import BaseInventoryPlugin\n" +
                "\n" +
                "class InventoryModule(BaseInventoryPlugin):\n" +
                "    NAME = 'test_plugin'\n" +
                "    def parse(self, inventory, loader, path, cache=True):\n" +
                "        super(InventoryModule, self).parse(inventory, loader, path)\n" +
                "        inventory.add_host('host_from_python', group='python_group', port=2222)\n" +
                "        inventory.set_variable('host_from_python', 'foo', 'bar')\n" +
                "        inventory.set_variable('python_group', 'group_var', 'baz')\n";

        Files.writeString(collectionDir.resolve("test_plugin.py"), pythonPlugin);

        // 2. Create the YAML configuration for the plugin
        Path inventoryYaml = tempDir.resolve("my_inventory.yml");
        String yamlContent =
                "plugin: test_ns.test_coll.test_plugin\n" +
                "some_option: value\n";
        Files.writeString(inventoryYaml, yamlContent);

        // 3. Initialize the provider with the temporary collection path
        PythonInventoryProvider provider = new PythonInventoryProvider(List.of(tempDir.toString()));

        // 4. Verify supports()
        assertTrue(provider.supports(inventoryYaml.toString()));

        // 5. Load the inventory
        Inventory inventory = new Inventory(new Group("all"));
        provider.load(inventoryYaml.toString(), inventory);

        // 6. Verify results
        assertTrue(inventory.getGroup("python_group").isPresent(), "Group 'python_group' should exist");
        assertEquals("baz", inventory.getGroup("python_group").get().variables().get("group_var"));

        assertTrue(inventory.getHost("host_from_python").isPresent(), "Host 'host_from_python' should exist");
        assertEquals("bar", inventory.getHost("host_from_python").get().variables().get("foo"));

        // port is handled via ansible_port variable in InventoryData.add_host mock
        Object port = inventory.getHost("host_from_python").get().variables().get("ansible_port");
        assertNotNull(port);
        assertEquals(2222, ((Number) port).intValue());

        // Verify host is in group
        assertTrue(inventory.getGroup("python_group").get().hosts().stream()
                .anyMatch(h -> h.name().equals("host_from_python")));
    }

    @Test
    void testInventoryManager_WithPythonPlugin() throws IOException {
        // Setup similar to above but use InventoryManager
        Path collectionDir = tempDir.resolve("ansible_collections/test_ns/test_coll/plugins/inventory");
        Files.createDirectories(collectionDir);
        Files.writeString(collectionDir.resolve("test_plugin.py"),
                "from ansible.plugins.inventory import BaseInventoryPlugin\n" +
                "class InventoryModule(BaseInventoryPlugin):\n" +
                "    def parse(self, inventory, loader, path, cache=True):\n" +
                "        inventory.add_host('manager_host', group='manager_group')\n");

        Path inventoryYaml = tempDir.resolve("plugin_inv.yml");
        Files.writeString(inventoryYaml, "plugin: test_ns.test_coll.test_plugin\n");

        InventoryManager manager = new InventoryManager();
        // Use default collection paths + our temp dir
        List<String> paths = PythonEnv.getCollectionPaths(null);
        paths.add(tempDir.toString());

        manager.addProvider(new PythonInventoryProvider(paths));
        manager.addProvider(new FileInventoryProvider());

        Inventory inventory = manager.loadInventory(List.of(inventoryYaml.toString()));

        assertTrue(inventory.getHost("manager_host").isPresent());
        assertTrue(inventory.getGroup("manager_group").isPresent());
    }
}
