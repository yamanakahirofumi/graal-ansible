package org.example.ansible.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DirectoryInventoryTest {

    @TempDir
    Path tempDir;

    @Test
    public void testDirectoryLoading() throws IOException {
        // Create an INI inventory file
        Path iniFile = tempDir.resolve("01-hosts.ini");
        Files.writeString(iniFile, "[web]\nhost1 ansible_host=127.0.0.1\n");

        // Create a YAML inventory file
        Path yamlFile = tempDir.resolve("02-hosts.yml");
        Files.writeString(yamlFile, "db:\n  hosts:\n    host2:\n      ansible_host: 127.0.0.2\n");

        // Create a subdirectory with another inventory file
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Path subIniFile = subDir.resolve("03-more-hosts.ini");
        Files.writeString(subIniFile, "[app]\nhost3\n");

        // Create a file that should be ignored (not supported by any provider)
        Path ignoredFile = tempDir.resolve("ignored.txt.bak");
        Files.writeString(ignoredFile, "this should be ignored");

        InventoryManager inventoryManager = new InventoryManager();
        inventoryManager.addProvider(new FileInventoryProvider());
        // We don't add ScriptInventoryProvider here to keep it simple,
        // but it would work similarly if we had an executable script.

        Inventory inventory = inventoryManager.loadInventory(List.of(tempDir.toString()));

        // Verify hosts from all files are present
        assertTrue(inventory.getHost("host1").isPresent(), "host1 should be loaded from INI");
        assertTrue(inventory.getHost("host2").isPresent(), "host2 should be loaded from YAML");
        assertTrue(inventory.getHost("host3").isPresent(), "host3 should be loaded from subdir INI");

        // Verify variables
        assertEquals("127.0.0.1", inventory.getHost("host1").get().variables().get("ansible_host"));
        assertEquals("127.0.0.2", inventory.getHost("host2").get().variables().get("ansible_host"));

        // Verify groups
        assertTrue(inventory.getGroup("web").isPresent());
        assertTrue(inventory.getGroup("db").isPresent());
        assertTrue(inventory.getGroup("app").isPresent());

        assertTrue(inventory.getGroup("web").get().hosts().stream().anyMatch(h -> h.name().equals("host1")));
        assertTrue(inventory.getGroup("db").get().hosts().stream().anyMatch(h -> h.name().equals("host2")));
        assertTrue(inventory.getGroup("app").get().hosts().stream().anyMatch(h -> h.name().equals("host3")));
    }

    @Test
    public void testEmptyDirectory() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectory(emptyDir);

        InventoryManager inventoryManager = new InventoryManager();
        inventoryManager.addProvider(new FileInventoryProvider());

        Inventory inventory = inventoryManager.loadInventory(List.of(emptyDir.toString()));

        assertTrue(inventory.all().hosts().isEmpty());
        // 'all' group itself might have default children if any were pre-created, but here it should be empty
        assertEquals(0, inventory.all().children().size());
    }
}
