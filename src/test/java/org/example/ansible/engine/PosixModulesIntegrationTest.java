package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class PosixModulesIntegrationTest {

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
        play = new Play("POSIX Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testGetentModule() {
        Task task = new Task("Getent passwd", "getent", Map.of(
                "database", "passwd",
                "key", "root"
        ));

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "getent failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be returned by getent");
        assertNotNull(facts.get("getent_passwd"), "getent_passwd key should exist in facts");
    }

    @Test
    void testCronModuleCheckMode() {
        // Skip if crontab binary is not present in the system environment
        boolean hasCrontab = false;
        try {
            Process p = new ProcessBuilder("which", "crontab").start();
            hasCrontab = (p.waitFor() == 0);
        } catch (Exception ignored) {}

        if (!hasCrontab) {
            return;
        }

        Task taskCheck = new Task("Add cron job in check mode", "cron", Map.of(
                "name", "check_mode_test_job",
                "job", "echo hello",
                "minute", "0",
                "hour", "12"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult resultCheck = taskExecutor.execute(play, host, taskCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultCheck.success(), "cron check mode failed: " + resultCheck.message() + " Data: " + resultCheck.data());
    }

    @Test
    void testPackageFactsModule() {
        Task task = new Task("Gather package facts", "package_facts", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "package_facts failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be returned by package_facts");
        assertTrue(facts.containsKey("packages"), "ansible_facts should contain packages key");
    }

    @Test
    void testServiceFactsModule() {
        Task task = new Task("Gather service facts", "service_facts", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "service_facts failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be returned by service_facts");
        assertTrue(facts.containsKey("services"), "ansible_facts should contain services key");
    }

    @Test
    void testHostnameModuleCheckMode() {
        Task task = new Task("Set hostname in check mode", "hostname", Map.of(
                "name", "test-hostname"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "hostname check mode failed: " + result.message() + " Data: " + result.data());
    }

    @Test
    void testKnownHostsModuleCheckMode() throws IOException {
        Path knownHostsFile = tempDir.resolve("known_hosts");
        Files.writeString(knownHostsFile, "example.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC...\n");

        Task task = new Task("Manage known_hosts in check mode", "known_hosts", Map.of(
                "name", "example.com",
                "key", "example.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC...",
                "path", knownHostsFile.toString(),
                "state", "present"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(result.success(), "known_hosts check mode failed: " + result.message() + " Data: " + result.data());
    }

    @Test
    void testDpkgSelectionsModule() {
        Task task = new Task("Set dpkg selections", "dpkg_selections", Map.of(
                "name", "sed",
                "selection", "install"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("dpkg_selections failed: " + result.message());
            System.err.println("Full Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        assertNotNull(result.data().get("before"));
        assertNotNull(result.data().get("after"));
    }

    @Test
    void testSetupModule() {
        Task task = new Task("Gather facts with filter", "setup", Map.of(
                "gather_subset", List.of("min"),
                "filter", List.of("ansible_system")
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), "Execution failed: " + result.message() + " Data: " + result.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should not be null");
        assertTrue(facts.containsKey("ansible_system"), "ansible_facts should contain ansible_system when filtered");
    }

    @Test
    void testScriptActionPlugin() throws IOException {
        System.setProperty("ansible.action_plugins.enabled", "true");
        try {
            Path tempScript = tempDir.resolve("test_script.sh");
            Files.writeString(tempScript, "#!/bin/sh\necho 'hello world'");
            tempScript.toFile().setExecutable(true);

            Task task = new Task("Test Script", "script", Map.of("_raw_params", tempScript.toString()));
            TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);
            assertTrue(result.success(), result.message());
            assertTrue(result.changed());
            assertTrue(result.data().get("stdout").toString().contains("hello world"));
        } finally {
            System.clearProperty("ansible.action_plugins.enabled");
        }
    }
}
