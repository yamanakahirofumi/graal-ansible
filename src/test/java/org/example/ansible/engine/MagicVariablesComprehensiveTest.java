package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MagicVariablesComprehensiveTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void testMagicVariables() throws IOException {
        Host host1 = new Host("host1");
        host1.variables().put("var1", "val1");
        Host host2 = new Host("host2");
        host2.variables().put("var2", "val2");

        Group allGroup = new Group("all", List.of(host1, host2), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        Map<String, Object> cliVars = new HashMap<>();
        cliVars.put("ansible_diff_mode", true);
        cliVars.put("ansible_verbosity", 1);

        VariableManager manager = new VariableManager(inventory, cliVars, Map.of(), tempDir, tempDir);
        manager.setAnsiblePlayHostsAll(List.of("host1", "host2"));
        manager.setAnsiblePlayHosts(List.of("host1", "host2"));
        manager.setAnsiblePlayBatch(List.of("host1", "host2"));

        VariableResolver resolver = new VariableResolver();

        // Test host1's variables
        Map<String, Object> vars1 = manager.getAllVariables(null, host1, null, null);

        // ansible_version
        Map<String, Object> version = (Map<String, Object>) vars1.get("ansible_version");
        assertNotNull(version);
        assertEquals("2.21.0", version.get("full"));

        // ansible_diff_mode
        assertEquals(true, vars1.get("ansible_diff_mode"));

        // hostvars
        Map<String, Object> hostvars = (Map<String, Object>) vars1.get("hostvars");
        assertNotNull(hostvars);
        assertTrue(hostvars.containsKey("host2"));
        Map<String, Object> host2Vars = (Map<String, Object>) hostvars.get("host2");
        assertEquals("val2", host2Vars.get("var2"));

        // play-scoped lists
        assertEquals(List.of("host1", "host2"), vars1.get("ansible_play_hosts"));
        assertEquals(List.of("host1", "host2"), vars1.get("ansible_play_batch"));
        assertEquals(List.of("host1", "host2"), vars1.get("ansible_play_hosts_all"));
    }

    @Test
    void testHostListUpdateInTQM() {
        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Group allGroup = new Group("all", List.of(host1, host2), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);

        VariableManager manager = new VariableManager(inventory, Map.of());

        // Mock TaskExecutor that fails for host1
        ITaskExecutor mockExecutor = new ITaskExecutor() {
            @Override
            public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, org.example.ansible.connection.Connection connection, org.example.ansible.connection.ConnectionFactory connectionFactory) {
                if (host.name().equals("host1")) {
                    return TaskResult.failure("Failed");
                }
                return TaskResult.success(false, Map.of());
            }

            @Override public org.example.ansible.util.OSHandler getOsHandler() { return null; }
            @Override public VariableResolver getVariableResolver() { return new VariableResolver(); }
            @Override public VariableManager getVariableManager() { return manager; }
            @Override public String resolveLocalPath(String path) { return path; }
            @Override public TaskResult execute(Task task, org.example.ansible.connection.BecomeContext becomeContext, Map<String, String> environment) { return null; }
            @Override public TaskResult execute(Task task, org.example.ansible.connection.BecomeContext becomeContext, org.example.ansible.connection.Connection connection, Map<String, String> environment) { return null; }
            @Override public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) { return null; }
            @Override public void close() {}
        };

        TaskQueueManager tqm = new TaskQueueManager(mockExecutor, (host, vars) -> new org.example.ansible.connection.Connection() {
            @Override public void connect() {}
            @Override public org.example.ansible.connection.ConnectionResult execCommand(String command, org.example.ansible.connection.BecomeContext becomeContext, java.util.Map<String, String> environment) { return null; }
            @Override public void putFile(java.nio.file.Path localPath, String remotePath) {}
            @Override public void fetchFile(String remotePath, java.nio.file.Path localPath) {}
            @Override public void close() {}
        });

        List<Task> tasks = List.of(new Task("task1", "ping", Map.of()));
        Play play = new Play("test play", "all", tasks, Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null);

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, manager, results, false);

        // After host1 fails, ansible_play_hosts should only contain host2
        Map<String, Object> vars2 = manager.getAllVariables(play, host2, null, null);
        assertEquals(List.of("host2"), vars2.get("ansible_play_hosts"));
        assertEquals(List.of("host1", "host2"), vars2.get("ansible_play_hosts_all"));
    }
}
