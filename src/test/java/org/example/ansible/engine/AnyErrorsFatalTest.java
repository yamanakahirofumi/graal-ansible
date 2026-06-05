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

class AnyErrorsFatalTest {

    private TaskExecutor taskExecutor;
    private PlaybookExecutor playbookExecutor;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        playbookExecutor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());
        inventory = new Inventory(new Group("all", List.of(new Host("localhost"), new Host("127.0.0.1")), List.of(), Map.of()));
    }

    @AfterEach
    void tearDown() {
        taskExecutor.close();
    }

    @Test
    void testAnyErrorsFatalPlayLevel() {
        String playbookYaml = """
                - name: test any_errors_fatal play level
                  hosts: all
                  any_errors_fatal: true
                  tasks:
                    - name: fail on localhost
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'localhost'
                    - name: should not run on 127.0.0.1
                      debug:
                        msg: "this should not appear"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        // Localhost failed at first task
        List<TaskResult> host1Results = results.get("localhost");
        assertNotNull(host1Results, "localhost results should not be null");
        assertFalse(host1Results.get(0).success());

        // 127.0.0.1 skipped first task (due to when), but should NOT run second task due to any_errors_fatal
        List<TaskResult> host2Results = results.get("127.0.0.1");
        assertNotNull(host2Results, "127.0.0.1 results should not be null");
        assertEquals(1, host2Results.size(), "127.0.0.1 should only have result for the first task (skipped)");
        assertTrue(host2Results.get(0).isSkipped());
    }

    @Test
    void testAnyErrorsFatalTaskLevel() {
        String playbookYaml = """
                - name: test any_errors_fatal task level
                  hosts: all
                  tasks:
                    - name: fail on localhost with fatal
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'localhost'
                      any_errors_fatal: true
                    - name: should not run on 127.0.0.1
                      debug:
                        msg: "this should not appear"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        List<TaskResult> host1Results = results.get("localhost");
        assertFalse(host1Results.get(0).success());

        List<TaskResult> host2Results = results.get("127.0.0.1");
        assertNotNull(host2Results);
        assertEquals(1, host2Results.size(), "127.0.0.1 should only have result for the first task (skipped)");
    }

    @Test
    void testDefaultBehaviorNotFatal() {
        String playbookYaml = """
                - name: test default behavior (not fatal)
                  hosts: all
                  tasks:
                    - name: fail on localhost
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'localhost'
                    - name: should run on 127.0.0.1
                      debug:
                        msg: "this should appear"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        List<TaskResult> host1Results = results.get("localhost");
        assertEquals(1, host1Results.size(), "localhost should stop after first task failure");
        assertFalse(host1Results.get(0).success());

        List<TaskResult> host2Results = results.get("127.0.0.1");
        assertEquals(2, host2Results.size(), "127.0.0.1 should continue to the second task");
        assertTrue(host2Results.get(0).isSkipped());
        assertTrue(host2Results.get(1).success());
        assertEquals("this should appear", host2Results.get(1).data().get("msg"));
    }

    @Test
    void testAnyErrorsFatalWithTemplate() {
        String playbookYaml = """
                - name: test any_errors_fatal with template
                  hosts: all
                  vars:
                    is_fatal: true
                  any_errors_fatal: "{{ is_fatal }}"
                  tasks:
                    - name: fail on localhost
                      fail:
                        msg: "forced failure"
                      when: inventory_hostname == 'localhost'
                    - name: should not run on 127.0.0.1
                      debug:
                        msg: "this should not appear"
                """;
        Playbook playbook = new YamlParser().parse(new ByteArrayInputStream(playbookYaml.getBytes(StandardCharsets.UTF_8)));

        Map<String, List<TaskResult>> results = playbookExecutor.execute(playbook, inventory);

        List<TaskResult> host2Results = results.get("127.0.0.1");
        assertNotNull(host2Results);
        assertEquals(1, host2Results.size());
    }
}
