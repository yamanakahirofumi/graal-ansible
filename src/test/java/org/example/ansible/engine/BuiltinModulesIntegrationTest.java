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

class BuiltinModulesIntegrationTest {

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
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testPauseModule() {
        Task task = new Task("Pause task", "pause", Map.of(
                "seconds", 1,
                "prompt", "Testing pause"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Pause execution failed: " + result.message() + " Data: " + result.data());
        assertFalse(result.changed(), "Pause module should report changed = false");
    }

    @Test
    void testWaitForModuleAndCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("wait_for_test.txt");

        // 1. wait_for file present check mode
        Files.writeString(targetFile, "content");
        Task taskCheck = new Task("Wait for file check mode", "wait_for", Map.of(
                "path", targetFile.toString(),
                "state", "present",
                "timeout", 5
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult resultCheck = taskExecutor.execute(play, host, taskCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultCheck.success(), "wait_for check mode failed: " + resultCheck.message() + " Data: " + resultCheck.data());

        // 2. wait_for file present execution mode
        Task taskRun = new Task("Wait for file run mode", "wait_for", Map.of(
                "path", targetFile.toString(),
                "state", "present",
                "timeout", 5
        ));
        TaskResult resultRun = taskExecutor.execute(play, host, taskRun, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultRun.success(), "wait_for run mode failed: " + resultRun.message() + " Data: " + resultRun.data());
    }

    @Test
    void testValidateArgumentSpecModule() {
        Map<String, Object> argumentSpec = Map.of(
                "name", Map.of("type", "str", "required", true),
                "count", Map.of("type", "int", "default", 1)
        );
        Map<String, Object> providedArguments = Map.of(
                "name", "test_item",
                "count", 5
        );

        Task task = new Task("Validate spec", "validate_argument_spec", Map.of(
                "argument_spec", argumentSpec,
                "provided_arguments", providedArguments
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "validate_argument_spec failed: " + result.message() + " Data: " + result.data());
    }

    @Test
    void testGroupByModule() {
        Task task = new Task("Group hosts", "group_by", Map.of(
                "key", "webservers"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "group_by failed: " + result.message() + " Data: " + result.data());
    }

    @Test
    void testSetFactModule() {
        Task task = new Task("Set facts", "set_fact", Map.of(
                "custom_app_port", 8080,
                "custom_app_name", "myapp"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "set_fact failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be returned by set_fact");
        assertEquals(8080, facts.get("custom_app_port"));
        assertEquals("myapp", facts.get("custom_app_name"));
    }

    @Test
    void testAssertAndFailModules() {
        // 1. assert success
        Task taskAssertSuccess = new Task("Assert true condition", "assert", Map.of(
                "that", List.of("1 == 1", "'hello' != 'world'")
        ));
        TaskResult resultAssertSuccess = taskExecutor.execute(play, host, taskAssertSuccess, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultAssertSuccess.success(), "assert success task failed: " + resultAssertSuccess.message());

        // 2. assert failure
        Task taskAssertFail = new Task("Assert false condition", "assert", Map.of(
                "that", List.of("1 == 2")
        ));
        TaskResult resultAssertFail = taskExecutor.execute(play, host, taskAssertFail, variableManager, false, null, null, new LocalConnection(), null);
        assertFalse(resultAssertFail.success(), "assert fail task should have failed");

        // 3. fail module
        Task taskFail = new Task("Explicit fail module", "fail", Map.of(
                "msg", "Custom failure message"
        ));
        TaskResult resultFail = taskExecutor.execute(play, host, taskFail, variableManager, false, null, null, new LocalConnection(), null);
        assertFalse(resultFail.success(), "fail module should return failed = true");
        assertTrue(resultFail.message().contains("Custom failure message") || resultFail.data().getOrDefault("msg", "").toString().contains("Custom failure message"));
    }

    @Test
    void testIncludeVarsModule() throws IOException {
        Path varsFile = tempDir.resolve("vars.yml");
        Files.writeString(varsFile, "database_port: 5432\ndatabase_host: localhost\n");

        Task taskIncludeVars = new Task("Include vars file", "include_vars", Map.of(
                "file", varsFile.toString()
        ));

        TaskResult resultIncludeVars = taskExecutor.execute(play, host, taskIncludeVars, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultIncludeVars.success(), "include_vars failed: " + resultIncludeVars.message() + " Data: " + resultIncludeVars.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) resultIncludeVars.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be returned by include_vars");
        assertEquals(5432, facts.get("database_port"));
        assertEquals("localhost", facts.get("database_host"));
    }
}
