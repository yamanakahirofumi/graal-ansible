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
        assertTrue(provider.supports(iniFile.toString()));

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
        assertEquals(5432L, inventory.getGroup("db").get().variables().get("db_port"));
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
                "print(json.dumps({\n" +
                "  'web': {'hosts': ['web1'], 'vars': {'script_var': 'from_script'}},\n" +
                "  'db': {'hosts': ['db1']}\n" +
                "}))";
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
}
