package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaxFailPercentageTest {

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());
        // 4 hosts inventory
        inventory = new Inventory(new Group("all", List.of(
                new Host("host1"), new Host("host2"), new Host("host3"), new Host("host4")
        ), List.of(), Map.of()));
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testMaxFailPercentagePlayLevel() {
        // max_fail_percentage: 25 -> more than 1 host failure is fatal (since 1/4 = 25%)
        // We will fail 2 hosts.
        String playbookYaml = """
                - name: test max_fail_percentage play level
                  hosts: all
                  max_fail_percentage: 25
                  tasks:
                    - name: fail on host1 and host2
                      shell: exit 1
                      when: inventory_hostname == 'host1' or inventory_hostname == 'host2'
                    - name: should not run anywhere
                      command: echo "should not run"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // First task results
        assertFalse(results.get("host1").get(0).success());
        assertFalse(results.get("host2").get(0).success());
        assertTrue(results.get("host3").get(0).isSkipped());
        assertTrue(results.get("host4").get(0).isSkipped());

        // Second task should NOT run because failure rate (2/4 = 50%) > 25%
        assertEquals(1, results.get("host1").size());
        assertEquals(1, results.get("host2").size());
        assertEquals(1, results.get("host3").size());
        assertEquals(1, results.get("host4").size());
    }

    @Test
    void testMaxFailPercentageJustMet() {
        // max_fail_percentage: 50 -> failure rate must be STRICTLY GREATER than 50% to be fatal.
        // We will fail 2 hosts (50%). It should CONTINUE.
        String playbookYaml = """
                - name: test max_fail_percentage just met
                  hosts: all
                  max_fail_percentage: 50
                  tasks:
                    - name: fail on host1 and host2
                      shell: exit 1
                      when: inventory_hostname == 'host1' or inventory_hostname == 'host2'
                    - name: should run on host3 and host4
                      command: echo "should run"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Second task should run because 2/4 = 50% is NOT > 50%
        assertEquals(2, results.get("host3").size());
        assertEquals(2, results.get("host4").size());
        assertTrue(results.get("host3").get(1).success());
    }

    @Test
    void testMaxFailPercentageWithSerial() {
        // 4 hosts, serial: 2 -> batches of [host1, host2], [host3, host4]
        // max_fail_percentage: 49 -> in batch of 2, 1 failure is 50%, which is > 49% -> fatal for the batch and play
        String playbookYaml = """
                - name: test max_fail_percentage with serial
                  hosts: all
                  serial: 2
                  max_fail_percentage: 49
                  tasks:
                    - name: fail on host1
                      shell: exit 1
                      when: inventory_hostname == 'host1'
                    - name: should not run on host2
                      command: echo "should not run"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Batch 1: [host1, host2]
        // Task 1 runs on host1 (fails) and host2 (skips)
        // Then checkMaxFailPercentage is called. failed=1, total=2 -> 50% > 49% -> fatal.

        assertFalse(results.get("host1").get(0).success());
        assertTrue(results.get("host2").get(0).isSkipped());

        // Second task in Batch 1 should NOT run on host2
        assertEquals(1, results.get("host2").size());

        // Batch 2 should NOT run at all
        assertNull(results.get("host3"));
        assertNull(results.get("host4"));
    }

    @Test
    void testMaxFailPercentageWithFreeStrategy() {
        // 4 hosts, free strategy
        // max_fail_percentage: 49 -> 2 failures (50%) > 49% -> fatal.
        String playbookYaml = """
                - name: test max_fail_percentage with free strategy
                  hosts: all
                  strategy: free
                  max_fail_percentage: 49
                  tasks:
                    - name: run task 1
                      test_max_fail:
                        host: "{{ inventory_hostname }}"
                    - name: should not run anywhere
                      test_max_fail_second:
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

        taskExecutor.registerModule("test_max_fail", (args, become, context) -> {
            String hostName = (String) args.get("host");
            if ("host1".equals(hostName) || "host2".equals(hostName)) {
                latch.countDown();
                return TaskResult.failure("failed");
            } else {
                try {
                    latch.await();
                    // Sleep briefly to ensure host1 and host2 have finished checkMaxFailPercentage
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return TaskResult.success(false, Map.of());
            }
        });
        taskExecutor.registerModule("test_max_fail_second", (args, become, context) -> TaskResult.success(false, Map.of()));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Second task should NOT run because failure rate (2/4 = 50%) > 49%
        // Host1 and Host2 failed the first task.
        // Host3 and Host4 should see playFatalError before they start the second task because of the sleep/coordination.

        assertEquals(1, results.get("host1").size(), "Host1 should have exactly 1 task result");
        assertEquals(1, results.get("host2").size(), "Host2 should have exactly 1 task result");
        assertTrue(results.get("host3") == null || results.get("host3").size() <= 1, "Host3 should have at most 1 task result");
        assertTrue(results.get("host4") == null || results.get("host4").size() <= 1, "Host4 should have at most 1 task result");
    }

    @Test
    void testMaxFailPercentageInRole() throws IOException {
        // Setup role
        Path rolesDir = tempDir.resolve("roles");
        Path testRoleDir = rolesDir.resolve("fail_role");
        Files.createDirectories(testRoleDir.resolve("tasks"));
        Files.writeString(testRoleDir.resolve("tasks").resolve("main.yml"), """
                - name: fail role task
                  shell: exit 1
                  when: inventory_hostname == 'host1' or inventory_hostname == 'host2'
                - name: should not run
                  command: echo "should not run"
                """);

        // Use PlaybookExecutor with baseDir to find roles
        PlaybookExecutor pe = new PlaybookExecutor(taskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        String playbookYaml = """
                - name: test max_fail_percentage in role
                  hosts: all
                  max_fail_percentage: 49
                  roles:
                    - fail_role
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = pe.execute(playbook, inventory, vm, false);

        // Role Task 1 runs on all hosts (fails on 2)
        assertFalse(results.get("host1").get(0).success());
        assertFalse(results.get("host2").get(0).success());

        // Role Task 2 should NOT run because failure rate (2/4 = 50%) > 49%
        assertEquals(1, results.get("host1").size(), "Host1 should only have 1 role task result");
        assertEquals(1, results.get("host2").size(), "Host2 should only have 1 role task result");
        assertEquals(1, results.get("host3").size(), "Host3 should only have 1 role task result");
        assertEquals(1, results.get("host4").size(), "Host4 should only have 1 role task result");
    }
}
