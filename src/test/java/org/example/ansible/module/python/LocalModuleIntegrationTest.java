package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.Play;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskQueueManager;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.VariableManager;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalModuleIntegrationTest {

    private TaskExecutor taskExecutor;
    private LocalConnection connection;
    private VariableManager variableManager;
    private Inventory inventory;
    private Host host;
    private Play play;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        taskExecutor = new TaskExecutor();
        connection = new LocalConnection();
        host = new Host("localhost");
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Local Integration Play", "all", List.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testFailModule() {
        Task task = new Task("test_fail", "fail", Map.of("msg", "Intentional Failure"));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, connection, null);

        assertFalse(result.success(), "Fail module should return failure");
        assertEquals("Intentional Failure", result.data().get("msg"));
    }

    // @Test
    // void testGatherFactsModule() {
    //     Task task = new Task("test_gather_facts", "gather_facts", Map.of());
    //
    //     // gather_facts is an Action Plugin.
    //     // We use TaskQueueManager to properly handle the results (adding facts to VariableManager)
    //     TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, v) -> connection);
    //     Map<String, List<TaskResult>> results = new HashMap<>();
    //
    //     Play playWithGatherFacts = new Play("gather facts play", "all", List.of(task));
    //     tqm.executePlay(playWithGatherFacts, inventory, variableManager, results, false);
    //
    //     List<TaskResult> hostResults = results.get("localhost");
    //     assertNotNull(hostResults);
    //     TaskResult result = hostResults.get(0);
    //     assertTrue(result.success(), "gather_facts failed: " + result.message() + " - " + result.data());
    //
    //     Map<String, Object> facts = variableManager.getVariablesForHost("localhost");
    //     assertTrue(facts.containsKey("ansible_facts"), "ansible_facts should be populated in variable manager");
    //     Map<String, Object> ansibleFacts = (Map<String, Object>) facts.get("ansible_facts");
    //     assertTrue(ansibleFacts.containsKey("ansible_os_family"));
    // }

    @Test
    void testGetUrlModule() throws IOException {
        Path srcFile = tempDir.resolve("get_url_src.txt");
        Files.writeString(srcFile, "get_url content");
        Path destFile = tempDir.resolve("get_url_dest.txt");

        String url = srcFile.toUri().toString();

        Task task = new Task("test_get_url", "get_url", Map.of(
                "url", url,
                "dest", destFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, connection, null);

        assertTrue(result.success(), "get_url should succeed: " + result.message());
        assertTrue(Files.exists(destFile));
        assertEquals("get_url content", Files.readString(destFile).trim());
    }

    @Test
    void testAssembleModule() throws IOException {
        Path srcDir = tempDir.resolve("assemble_src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("file1.txt"), "part1\n");
        Files.writeString(srcDir.resolve("file2.txt"), "part2\n");
        Path destFile = tempDir.resolve("assembled.txt");

        Task task = new Task("test_assemble", "assemble", Map.of(
                "src", srcDir.toAbsolutePath().toString(),
                "dest", destFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, connection, null);

        assertTrue(result.success(), "assemble should succeed: " + result.message());
        String content = Files.readString(destFile);
        assertTrue(content.contains("part1"));
        assertTrue(content.contains("part2"));
    }

    @Test
    void testScriptModule() throws IOException {
        Path scriptFile = tempDir.resolve("myscript.sh");
        Files.writeString(scriptFile, "#!/bin/sh\necho 'hello from script'");
        scriptFile.toFile().setExecutable(true);

        Task task = new Task("test_script", "script", Map.of(
                "_raw_params", scriptFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, connection, null);

        assertTrue(result.success(), "script should succeed: " + result.message());
        assertTrue(result.data().get("stdout").toString().contains("hello from script"));
    }

    // @Test
    // void testPackageFactsModule() {
    //     Task task = new Task("test_package_facts", "package_facts", Map.of());
    //
    //     // package_facts may need a play for become logic in TaskExecutor.execute
    //     TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, connection, null);
    //
    //     assertTrue(result.success(), "package_facts should succeed: " + (result.data().containsKey("msg") ? result.data().get("msg") : result.message()) + " - " + result.data());
    //     Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
    //     assertNotNull(facts);
    //     assertTrue(facts.containsKey("packages"));
    // }

    @Test
    void testAddHostModule() {
        Task task = new Task("test_add_host", "add_host", Map.of(
                "name", "new_dynamic_host",
                "groups", "dynamic_group",
                "custom_var", "custom_val"
        ));

        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, v) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        Play playWithAddHost = new Play("add host play", "all", List.of(task));
        tqm.executePlay(playWithAddHost, inventory, variableManager, results, false);

        // Verify inventory update
        Host newHost = inventory.findHost("new_dynamic_host").orElse(null);
        assertNotNull(newHost, "Host should be added to inventory");
        assertEquals("custom_val", newHost.variables().get("custom_var"));

        Group group = inventory.findGroup("dynamic_group");
        assertNotNull(group, "Group should be created");
        assertTrue(group.hosts().contains(newHost), "Host should be in the group");
    }

    @Test
    void testGroupByModule() {
        // First, add some facts to the host so we can group by them
        variableManager.addFacts("localhost", Map.of("os_type", "linux_distro"));

        Task task = new Task("test_group_by", "group_by", Map.of(
                "key", "os_{{ os_type }}"
        ));

        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (h, v) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        Play playWithGroupBy = new Play("group by play", "all", List.of(task));
        tqm.executePlay(playWithGroupBy, inventory, variableManager, results, false);

        // Verify group creation
        Group group = inventory.findGroup("os_linux_distro");
        assertNotNull(group, "Group 'os_linux_distro' should be created");
        assertTrue(group.hosts().contains(host), "Original host should be in the new group");
    }
}
