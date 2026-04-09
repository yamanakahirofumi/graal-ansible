package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionModuleRuntimeTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Runtime Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testSetFact() {
        Task task = new Task("Test set_fact", "set_fact", Map.of(
                "my_var", "my_val",
                "another_var", "{{ 1 + 1 }}"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "set_fact failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertEquals("my_val", facts.get("my_var"));
        // Note: Action plugins might return templates as is if not evaluated by bridge,
        // but set_fact.py in Ansible usually gets resolved values or resolves them itself if it uses templar.
        // In our bridge, we pass a Templar with variables.
    }

    @Test
    void testFail() {
        Task task = new Task("Test fail", "fail", Map.of(
                "msg", "Expected failure"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success());
        assertEquals("Expected failure", result.data().get("msg"));
    }

    @Test
    void testGatherFacts() {
        Task task = new Task("Test gather_facts", "gather_facts", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "gather_facts failed: " + result.message() + " Data: " + result.data());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertTrue(facts.containsKey("ansible_os_family") || facts.containsKey("ansible_facts") || facts.containsKey("_ansible_facts_gathered"));
    }

    @Test
    void testSetup() {
        Task task = new Task("Test setup", "setup", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "setup failed: " + result.message() + " Data: " + result.data());
        assertNotNull(result.data().get("ansible_facts"));
    }

    @Test
    void testGroupBy() {
        Task task = new Task("Test group_by", "group_by", Map.of(
                "key", "custom_group"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "group_by failed: " + result.message());
        assertEquals("custom_group", result.data().get("add_group"));
    }

    @Test
    void testAssert() {
        Task task = new Task("Test assert", "assert", Map.of(
                "that", List.of("1 == 1")
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "assert failed: " + result.message());
    }

    @Test
    void testAssemble() throws java.io.IOException {
        java.nio.file.Path srcDir = tempDir.resolve("assemble_src");
        java.nio.file.Files.createDirectories(srcDir);
        java.nio.file.Files.writeString(srcDir.resolve("1.txt"), "one\n");
        java.nio.file.Files.writeString(srcDir.resolve("2.txt"), "two\n");
        java.nio.file.Path destFile = tempDir.resolve("assembled.txt");

        Task task = new Task("Test assemble", "assemble", Map.of(
                "src", srcDir.toString(),
                "dest", destFile.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "assemble failed: " + result.message());
        assertTrue(java.nio.file.Files.exists(destFile));
    }

    @Test
    void testAddHost() {
        Task task = new Task("Test add_host", "add_host", Map.of(
                "name", "new_guy",
                "groups", "just_added"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "add_host failed: " + result.message());
        assertEquals("new_guy", result.data().get("add_host"));
    }
}
