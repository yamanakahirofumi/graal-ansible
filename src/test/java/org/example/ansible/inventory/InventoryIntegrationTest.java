package org.example.ansible.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InventoryIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testFileInventoryProvider_Ini() throws IOException {
        Path iniFile = tempDir.resolve("hosts.ini");
        Files.writeString(iniFile, "[web]\nweb1 ansible_host=127.0.0.1\n[web:vars]\nfoo=bar");

        FileInventoryProvider provider = new FileInventoryProvider();
        // On Windows, canExecute() might be true for any file, making supports() return false.
        // For testing purposes, we might need to be careful, but let's see.
        // Actually, on Linux/macOS, we want to ensure it works.
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            assertTrue(provider.supports(iniFile.toString()));
        }

        Inventory inventory = new Inventory(new Group("all"));
        provider.load(iniFile.toString(), inventory);

        assertTrue(inventory.getGroup("web").isPresent());
        assertEquals("bar", inventory.getGroup("web").get().variables().get("foo"));
        assertTrue(inventory.getHost("web1").isPresent());
    }

    @Test
    void testScriptInventoryProvider() throws IOException {
        Path scriptFile = tempDir.resolve("inventory.py");
        String scriptContent = "#!/usr/bin/env python3\n" +
                "import json\n" +
                "print(json.dumps({\n" +
                "  'db': {\n" +
                "    'hosts': ['db1'],\n" +
                "    'vars': {'db_port': 5432}\n" +
                "  },\n" +
                "  '_meta': {\n" +
                "    'hostvars': {\n" +
                "      'db1': {'ansible_host': '10.0.0.1'}\n" +
                "    }\n" +
                "  }\n" +
                "}))";
        Files.writeString(scriptFile, scriptContent);
        scriptFile.toFile().setExecutable(true);

        ScriptInventoryProvider provider = new ScriptInventoryProvider();
        assertTrue(provider.supports(scriptFile.toString()));

        Inventory inventory = new Inventory(new Group("all"));
        provider.load(scriptFile.toString(), inventory);

        assertTrue(inventory.getGroup("db").isPresent());
        Object port = inventory.getGroup("db").get().variables().get("db_port");
        assertTrue(port instanceof Number);
        assertEquals(5432, ((Number) port).intValue());
        assertTrue(inventory.getHost("db1").isPresent());
        assertEquals("10.0.0.1", inventory.getHost("db1").get().variables().get("ansible_host"));
    }

    @Test
    void testInventoryManager_Merging() throws IOException {
        // Source 1: Static INI
        Path iniFile = tempDir.resolve("hosts.ini");
        Files.writeString(iniFile, "[web]\nweb1");

        // Source 2: Dynamic Script
        Path scriptFile = tempDir.resolve("inventory.py");
        String scriptContent = "#!/usr/bin/env python3\n" +
                "import json\n" +
                "import sys\n" +
                "if '--list' in sys.argv:\n" +
                "    print(json.dumps({\n" +
                "      'web': {'hosts': ['web1'], 'vars': {'script_var': 'from_script'}},\n" +
                "      'db': {'hosts': ['db1']}\n" +
                "    }))";
        Files.writeString(scriptFile, scriptContent);
        scriptFile.toFile().setExecutable(true);

        InventoryManager manager = new InventoryManager();
        manager.addProvider(new ScriptInventoryProvider());
        manager.addProvider(new FileInventoryProvider());

        Inventory inventory = manager.loadInventory(List.of(iniFile.toString(), scriptFile.toString()));

        assertTrue(inventory.getGroup("web").isPresent());
        assertTrue(inventory.getGroup("db").isPresent());
        assertEquals("from_script", inventory.getGroup("web").get().variables().get("script_var"));

        assertTrue(inventory.getHost("web1").isPresent());
        assertTrue(inventory.getHost("db1").isPresent());
    }

    @Test
    void testInventoryManager_RecursiveDirectory() throws IOException {
        Path invDir = tempDir.resolve("inventory");
        Files.createDirectories(invDir);

        // 1. Static INI in root
        Files.writeString(invDir.resolve("hosts.ini"), "[web]\nweb1");

        // 2. Static YAML in subdir
        Path subDir = invDir.resolve("subdir");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("hosts.yml"), "db:\n  hosts:\n    db1:");

        // 3. Dynamic script in subdir
        Path scriptFile = subDir.resolve("dynamic.py");
        String scriptContent = "#!/usr/bin/env python3\n" +
                "import json\n" +
                "import sys\n" +
                "if '--list' in sys.argv:\n" +
                "    print(json.dumps({\n" +
                "      'app': {'hosts': ['app1']}\n" +
                "    }))";
        Files.writeString(scriptFile, scriptContent);
        scriptFile.toFile().setExecutable(true);

        // 4. Files to ignore
        Files.writeString(invDir.resolve(".hidden"), "should ignore");
        Files.writeString(invDir.resolve("hosts.bak"), "should ignore");
        Files.createDirectories(invDir.resolve("group_vars"));

        InventoryManager manager = new InventoryManager();
        manager.addProvider(new ScriptInventoryProvider());
        manager.addProvider(new FileInventoryProvider());

        Inventory inventory = manager.loadInventory(List.of(invDir.toString()));

        // Check if all hosts from different files/scripts are present
        assertTrue(inventory.getHost("web1").isPresent(), "web1 from INI should be present");
        assertTrue(inventory.getHost("db1").isPresent(), "db1 from YAML should be present");
        assertTrue(inventory.getHost("app1").isPresent(), "app1 from script should be present");

        // Check groups
        assertTrue(inventory.getGroup("web").isPresent());
        assertTrue(inventory.getGroup("db").isPresent());
        assertTrue(inventory.getGroup("app").isPresent());
    }
}
