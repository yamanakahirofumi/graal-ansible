package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaxFailPercentageTest {

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
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'host1' or inventory_hostname == 'host2'
                    - name: should not run anywhere
                      debug:
                        msg: "this should not appear"
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
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'host1' or inventory_hostname == 'host2'
                    - name: should run on host3 and host4
                      debug:
                        msg: "this should appear"
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
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'host1'
                    - name: should not run on host2
                      debug:
                        msg: "this should not appear"
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
}
