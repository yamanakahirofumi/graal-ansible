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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicInventoryIntegrationTest {

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testAddHostBasicAndSubsequentPlay() {
        String yaml = """
            - name: Play 1 - Add Host
              hosts: localhost
              tasks:
                - name: Add new web node
                  add_host:
                    name: "web_node_1"
                    groups: "webservers"
                    ansible_connection: "local"
                    custom_port: 8080
                    app_env: "production"

            - name: Play 2 - Target New Web Node
              hosts: webservers
              tasks:
                - name: Verify custom vars
                  debug:
                    msg: "Port={{ custom_port }}, Env={{ app_env }}"
            """;

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());

        YamlParser parser = new YamlParser();
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        Playbook playbook = parser.parse(is);

        Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);

        // Verify web_node_1 exists in inventory under group webservers
        assertTrue(inventory.getHost("web_node_1").isPresent(), "Host web_node_1 should be added to inventory");
        assertTrue(inventory.getGroup("webservers").isPresent(), "Group webservers should be created");

        Host addedHost = inventory.getHost("web_node_1").get();
        assertEquals(8080, addedHost.variables().get("custom_port"));
        assertEquals("production", addedHost.variables().get("app_env"));

        // Verify Play 2 results on web_node_1
        List<TaskResult> hostResults = results.get("web_node_1");
        assertNotNull(hostResults, "Results should contain entries for web_node_1");
        assertTrue(hostResults.stream().anyMatch(r -> r.success() && "Port=8080, Env=production".equals(r.data().get("msg"))),
                "Subsequent play should execute on web_node_1 with custom variables");
    }

    @Test
    void testAddHostMultipleGroupsAndTemplatedVars() {
        String yaml = """
            - name: Play 1 - Add Host with Templated Args and Multiple Groups
              hosts: localhost
              vars:
                target_name: "db_node_1"
                env_tag: "staging"
              tasks:
                - name: Add db host
                  add_host:
                    hostname: "{{ target_name }}"
                    groups: "db_servers, {{ env_tag }}_nodes"
                    db_role: "primary"

            - name: Play 2 - Verify db_servers
              hosts: db_servers
              tasks:
                - name: Check db role
                  debug:
                    msg: "Role={{ db_role }}"

            - name: Play 3 - Verify staging_nodes
              hosts: staging_nodes
              tasks:
                - name: Check staging node
                  debug:
                    msg: "Staging node {{ inventory_hostname }} active"
            """;

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());

        YamlParser parser = new YamlParser();
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        Playbook playbook = parser.parse(is);

        Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);

        assertTrue(inventory.getHost("db_node_1").isPresent());
        assertTrue(inventory.getGroup("db_servers").isPresent());
        assertTrue(inventory.getGroup("staging_nodes").isPresent());

        List<TaskResult> dbResults = results.get("db_node_1");
        assertNotNull(dbResults);
        assertTrue(dbResults.stream().anyMatch(r -> r.success() && "Role=primary".equals(r.data().get("msg"))));
        assertTrue(dbResults.stream().anyMatch(r -> r.success() && "Staging node db_node_1 active".equals(r.data().get("msg"))));
    }

    @Test
    void testAddHostDefaultGroupAll() {
        String yaml = """
            - name: Add Host without group
              hosts: localhost
              tasks:
                - name: Add standalone host
                  add_host:
                    name: "standalone_1"
                    standalone_var: "active"
            """;

        Inventory inventory = new Inventory(new Group("all", List.of(new Host("localhost")), List.of(), Map.of()));
        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());

        YamlParser parser = new YamlParser();
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        Playbook playbook = parser.parse(is);

        executor.execute(playbook, inventory);

        assertTrue(inventory.getHost("standalone_1").isPresent());
        Host host = inventory.getHost("standalone_1").get();
        assertEquals("active", host.variables().get("standalone_var"));
    }

    @Test
    void testGroupByModule() {
        String yaml = """
            - name: Group Hosts by OS Type
              hosts: all
              tasks:
                - name: Group by os
                  group_by:
                    key: "os_{{ os_type }}"

            - name: Play for Linux Hosts
              hosts: os_linux
              tasks:
                - name: Linux Task
                  debug:
                    msg: "Linux node {{ inventory_hostname }}"

            - name: Play for Windows Hosts
              hosts: os_windows
              tasks:
                - name: Windows Task
                  debug:
                    msg: "Windows node {{ inventory_hostname }}"
            """;

        Host host1 = new Host("node1", Map.of("os_type", "linux"));
        Host host2 = new Host("node2", Map.of("os_type", "windows"));
        Host host3 = new Host("node3", Map.of("os_type", "linux"));

        Inventory inventory = new Inventory(new Group("all", List.of(host1, host2, host3), List.of(), Map.of()));
        PlaybookExecutor executor = new PlaybookExecutor(taskExecutor, (host, vars) -> new LocalConnection());

        YamlParser parser = new YamlParser();
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        Playbook playbook = parser.parse(is);

        Map<String, List<TaskResult>> results = executor.execute(playbook, inventory);

        assertTrue(inventory.getGroup("os_linux").isPresent());
        assertTrue(inventory.getGroup("os_windows").isPresent());

        // os_linux should contain node1 and node3
        List<Host> linuxHosts = inventory.getGroup("os_linux").get().hosts();
        assertEquals(2, linuxHosts.size());
        assertTrue(linuxHosts.stream().anyMatch(h -> "node1".equals(h.name())));
        assertTrue(linuxHosts.stream().anyMatch(h -> "node3".equals(h.name())));

        // os_windows should contain node2
        List<Host> windowsHosts = inventory.getGroup("os_windows").get().hosts();
        assertEquals(1, windowsHosts.size());
        assertEquals("node2", windowsHosts.get(0).name());

        // Verify task results for node1 and node2
        List<TaskResult> node1Results = results.get("node1");
        assertNotNull(node1Results);
        assertTrue(node1Results.stream().anyMatch(r -> r.success() && "Linux node node1".equals(r.data().get("msg"))));

        List<TaskResult> node2Results = results.get("node2");
        assertNotNull(node2Results);
        assertTrue(node2Results.stream().anyMatch(r -> r.success() && "Windows node node2".equals(r.data().get("msg"))));
    }
}
