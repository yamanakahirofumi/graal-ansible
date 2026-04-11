package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.*;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalModuleIntegrationTest {

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;
    private LocalConnection connection;
    private Host localhost;
    private Inventory inventory;
    private VariableManager variableManager;
    private Play play;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connection = new LocalConnection();
        localhost = new Host("localhost");
        inventory = new Inventory(new Group("all", new ArrayList<>(List.of(localhost)), new ArrayList<>(), new HashMap<>()));
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Local Test Play", "all", List.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testFailModule() {
        Task task = new Task("test_fail", "fail", Map.of("msg", "Expected failure"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertFalse(result.success(), "Fail module should return failure");
        assertEquals("Expected failure", result.data().get("msg"));
    }

    @Test
    void testGatherFactsModule() {
        Task task = new Task("test_gather_facts", "gather_facts", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "gather_facts should succeed: " + result.message() + " Data: " + result.data());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present");
        assertTrue(facts.containsKey("ansible_os_family"), "Should contain OS family fact");
    }

    @Test
    void testGetUrlModule() throws IOException {
        Path srcFile = tempDir.resolve("src.txt");
        Files.writeString(srcFile, "url content");

        Path destFile = tempDir.resolve("dest.txt");

        Task task = new Task("test_get_url", "get_url", Map.of(
                "url", srcFile.toUri().toString(),
                "dest", destFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "get_url should succeed: " + result.message());
        assertTrue(Files.exists(destFile), "Downloaded file should exist");
        assertEquals("url content", Files.readString(destFile).trim());
    }

    @Test
    void testScriptModule() throws IOException {
        Path scriptFile = tempDir.resolve("test_script.sh");
        Files.writeString(scriptFile, "#!/bin/sh\necho 'script output'");
        scriptFile.toFile().setExecutable(true);

        Task task = new Task("test_script", "script", Map.of(
                "_raw_params", scriptFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "script should succeed: " + result.message() + " Data: " + result.data());
        String stdout = (String) result.data().get("stdout");
        assertTrue(stdout.contains("script output"), "Output should contain script output. Got: " + stdout);
    }

    @Test
    void testPackageFactsModule() {
        Task task = new Task("test_package_facts", "package_facts", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        // We accept failure if package manager is not detected, but it shouldn't crash the bridge
        if (result.success()) {
            Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
            assertNotNull(facts, "ansible_facts should be present");
            assertTrue(facts.containsKey("packages"), "Should contain packages fact");
        } else {
            System.out.println("package_facts skipped or failed as expected in this environment: " + result.message());
        }
    }

    @Test
    void testAssembleModule() throws IOException {
        Path srcDir = tempDir.resolve("src_assemble");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("01.txt"), "part1\n");
        Files.writeString(srcDir.resolve("02.txt"), "part2\n");

        Path destFile = tempDir.resolve("assembled.txt");

        Task task = new Task("test_assemble", "assemble", Map.of(
                "src", srcDir.toAbsolutePath().toString(),
                "dest", destFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "assemble should succeed: " + result.message());
        String assembledContent = Files.readString(destFile);
        assertTrue(assembledContent.contains("part1"), "Should contain part1");
        assertTrue(assembledContent.contains("part2"), "Should contain part2");
    }

    @Test
    void testAddHostAndGroupBy() {
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, v) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        Play play = new Play("Dynamic Inventory Play", "all", List.of(
                new Task("add new host", "add_host", Map.of(
                        "name", "new_dynamic_host",
                        "groups", "dynamic_group",
                        "custom_var", "dynamic_val"
                )),
                new Task("group by OS", "group_by", Map.of(
                        "key", "custom_group_{{ ansible_os_family }}"
                ))
        ));

        variableManager.addFacts("localhost", Map.of("ansible_os_family", "TestOS"));

        tqm.executePlay(play, inventory, variableManager, results, false);

        Map<String, List<String>> groupsMap = inventory.getGroupsMap();
        assertTrue(groupsMap.containsKey("dynamic_group"), "dynamic_group should be created. Groups: " + groupsMap.keySet());
        assertTrue(groupsMap.get("dynamic_group").contains("new_dynamic_host"), "new_dynamic_host should be in dynamic_group");

        assertTrue(groupsMap.containsKey("custom_group_TestOS"), "group_by should create custom_group_TestOS");
        assertTrue(groupsMap.get("custom_group_TestOS").contains("localhost"), "localhost should be in custom_group_TestOS");
    }
}
