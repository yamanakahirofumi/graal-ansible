package org.example.ansible.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryDirectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void testRecursiveDirectoryScanning() throws IOException {
        // Setup directory structure
        // tempDir/
        //   hosts.ini
        //   subdir/
        //     other.yml
        //   vars/ (ignored)
        //     ignored.yml
        //   .hidden.ini (ignored)

        Files.writeString(tempDir.resolve("hosts.ini"), "[web]\nweb1");

        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("other.yml"), "db:\n  hosts:\n    db1:");

        Path varsDir = tempDir.resolve("vars");
        Files.createDirectories(varsDir);
        Files.writeString(varsDir.resolve("ignored.yml"), "foo: bar");

        Files.writeString(tempDir.resolve(".hidden.ini"), "[hidden]\nhost_hidden");
        Files.writeString(tempDir.resolve("temp.bak"), "[bak]\nhost_bak");

        InventoryManager manager = new InventoryManager();
        manager.addProvider(new FileInventoryProvider());

        Inventory inventory = manager.loadInventory(List.of(tempDir.toString()));

        assertTrue(inventory.getHost("web1").isPresent(), "web1 should be present from hosts.ini");
        assertTrue(inventory.getHost("db1").isPresent(), "db1 should be present from subdir/other.yml");

        assertFalse(inventory.getHost("host_hidden").isPresent(), "Hidden files should be ignored");
        assertFalse(inventory.getHost("host_bak").isPresent(), "Backup files should be ignored");

        assertTrue(inventory.getGroup("web").isPresent());
        assertTrue(inventory.getGroup("db").isPresent());
    }

    @Test
    void testAlphabeticalOrder() throws IOException {
        // a.ini: var=a
        // b.ini: var=b
        // If processed in order, var should be 'b'

        Files.writeString(tempDir.resolve("b.ini"), "[all:vars]\nvar=b");
        Files.writeString(tempDir.resolve("a.ini"), "[all:vars]\nvar=a");

        InventoryManager manager = new InventoryManager();
        manager.addProvider(new FileInventoryProvider());

        Inventory inventory = manager.loadInventory(List.of(tempDir.toString()));

        // Alphabetical order: a.ini then b.ini. So b should win.
        assertEquals("b", inventory.all().variables().get("var"));
    }

    @Test
    void testExplicitSourceStrictHandling() throws IOException {
        Path unsupportedFile = tempDir.resolve("unsupported.xyz");
        Files.writeString(unsupportedFile, "some random content");

        InventoryManager manager = new InventoryManager();
        // No providers that support .xyz

        assertThrows(RuntimeException.class, () -> {
            manager.loadInventory(List.of(unsupportedFile.toString()));
        }, "Explicitly provided unsupported source should throw exception");
    }

    @Test
    void testDiscoveredSourceLenientHandling() throws IOException {
        Path unsupportedFile = tempDir.resolve("unsupported.xyz");
        Files.writeString(unsupportedFile, "some random content");

        // Also add a supported file so we have something to load
        Files.writeString(tempDir.resolve("hosts.ini"), "host1");

        InventoryManager manager = new InventoryManager();
        manager.addProvider(new FileInventoryProvider());

        // Should not throw exception when discovering unsupported file in a directory
        Inventory inventory = assertDoesNotThrow(() -> {
            return manager.loadInventory(List.of(tempDir.toString()));
        });

        assertTrue(inventory.getHost("host1").isPresent());
    }
}
