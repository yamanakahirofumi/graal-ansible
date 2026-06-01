package org.example.ansible.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InventoryDirectoryTest {

    @TempDir
    Path tempDir;

    private InventoryManager inventoryManager;

    @BeforeEach
    void setUp() {
        inventoryManager = new InventoryManager();
        inventoryManager.addProvider(new FileInventoryProvider());
        inventoryManager.addProvider(new ScriptInventoryProvider());
    }

    @Test
    void testRecursiveDirectoryLoadingAndExclusions() throws IOException {
        // Create directory structure:
        // tempDir/
        //   a_hosts.ini  (group: a)
        //   B_hosts.yml  (group: b)
        //   subdir/
        //     c_hosts.ini (group: c)
        //   .hidden.ini   (ignored)
        //   backup.ini~   (ignored)
        //   vars/         (ignored)
        //     ignored.ini
        //   group_vars/   (ignored)
        //   host_vars/    (ignored)

        Files.writeString(tempDir.resolve("a_hosts.ini"), "[a]\nhost_a");
        Files.writeString(tempDir.resolve("B_hosts.yml"), "b:\n  hosts:\n    host_b:");

        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("c_hosts.ini"), "[c]\nhost_c");

        Files.writeString(tempDir.resolve(".hidden.ini"), "[hidden]\nhost_hidden");
        Files.writeString(tempDir.resolve("backup.ini~"), "[backup]\nhost_backup");

        Path varsDir = tempDir.resolve("vars");
        Files.createDirectories(varsDir);
        Files.writeString(varsDir.resolve("ignored.ini"), "[ignored]\nhost_ignored");

        Path groupVarsDir = tempDir.resolve("group_vars");
        Files.createDirectories(groupVarsDir);
        Files.writeString(groupVarsDir.resolve("ignored.ini"), "[ignored_group]\nhost_ignored_group");

        Inventory inventory = inventoryManager.loadInventory(List.of(tempDir.toString()));

        // Check if correct hosts/groups are loaded
        assertTrue(inventory.getGroup("a").isPresent(), "Group 'a' should be loaded from a_hosts.ini");
        assertTrue(inventory.getGroup("b").isPresent(), "Group 'b' should be loaded from B_hosts.yml");
        assertTrue(inventory.getGroup("c").isPresent(), "Group 'c' should be loaded from subdir/c_hosts.ini");

        // Check exclusions
        assertFalse(inventory.getGroup("hidden").isPresent(), "Hidden files should be ignored");
        assertFalse(inventory.getGroup("backup").isPresent(), "Backup files should be ignored");
        assertFalse(inventory.getGroup("ignored").isPresent(), "Files in 'vars/' directory should be ignored");
        assertFalse(inventory.getGroup("ignored_group").isPresent(), "Files in 'group_vars/' directory should be ignored");
    }

    @Test
    void testSortingOrder() throws IOException {
        // Files: z.ini, A.ini, m.ini
        // Expected order: A.ini, m.ini, z.ini (case-insensitive alphabetical)
        // We can verify order by seeing which variables override others if we define same group in all.

        Files.writeString(tempDir.resolve("z.ini"), "[test]\nhost1 var=z");
        Files.writeString(tempDir.resolve("A.ini"), "[test]\nhost1 var=A");
        Files.writeString(tempDir.resolve("m.ini"), "[test]\nhost1 var=m");

        Inventory inventory = inventoryManager.loadInventory(List.of(tempDir.toString()));

        Optional<Host> host = inventory.getHost("host1");
        assertTrue(host.isPresent());
        // Last one loaded wins for the same variable
        // Order should be A.ini -> m.ini -> z.ini
        // So 'z' should be the final value
        assertEquals("z", host.get().variables().get("var"));
    }

    @Test
    void testExplicitInvalidSourceStillThrowsException() {
        Path nonExistent = tempDir.resolve("non_existent.ini");
        assertThrows(RuntimeException.class, () -> inventoryManager.loadInventory(List.of(nonExistent.toString())));
    }
}
