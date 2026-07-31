package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HostPatternTest {

    @Test
    void testMultiplePatterns() {
        Host web1 = new Host("web1");
        Host web2 = new Host("web2");
        Host db1 = new Host("db1");

        Group web = new Group("web", List.of(web1, web2), List.of(), Map.of());
        Group db = new Group("db", List.of(db1), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, db), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        // Test comma separator
        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play1 = new Play("Play 1", "web1,db1", tasks);
        tqm.executePlay(play1, inventory, vm, results, false);
        assertTrue(results.containsKey("web1"), "Should contain web1");
        assertTrue(results.containsKey("db1"), "Should contain db1");
        assertFalse(results.containsKey("web2"), "Should NOT contain web2");

        // Test colon separator
        results.clear();
        Play play2 = new Play("Play 2", "web1:db1", tasks);
        tqm.executePlay(play2, inventory, vm, results, false);
        assertTrue(results.containsKey("web1"), "Should contain web1 (colon)");
        assertTrue(results.containsKey("db1"), "Should contain db1 (colon)");
        assertFalse(results.containsKey("web2"), "Should NOT contain web2 (colon)");
    }

    @Test
    void testWildcardPattern() {
        Host web1 = new Host("web1");
        Host web2 = new Host("web2");
        Host db1 = new Host("db1");

        Group web = new Group("web", List.of(web1, web2), List.of(), Map.of());
        Group db = new Group("db", List.of(db1), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, db), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        // Test web*
        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play = new Play("Play Wildcard", "web*", tasks);
        tqm.executePlay(play, inventory, vm, results, false);
        assertTrue(results.containsKey("web1"), "Should match web1");
        assertTrue(results.containsKey("web2"), "Should match web2");
        assertFalse(results.containsKey("db1"), "Should NOT match db1");
    }

    @Test
    void testLimitWithWildcard() {
        Host web1 = new Host("web1");
        Host web2 = new Host("web2");
        Host db1 = new Host("db1");

        Group web = new Group("web", List.of(web1, web2), List.of(), Map.of());
        Group db = new Group("db", List.of(db1), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, db), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        // Play for all, but limited to web*
        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play = new Play("Play All", "all", tasks);
        tqm.executePlay(play, inventory, vm, results, false, List.of(), List.of(), "web*");
        assertTrue(results.containsKey("web1"));
        assertTrue(results.containsKey("web2"));
        assertFalse(results.containsKey("db1"));
    }

    @Test
    void testWildcardGroupMatch() {
        Host host1 = new Host("host1");
        Host host2 = new Host("host2");
        Host host3 = new Host("host3");

        Group web_servers = new Group("web_servers", List.of(host1), List.of(), Map.of());
        Group db_servers = new Group("db_servers", List.of(host2), List.of(), Map.of());
        Group other = new Group("other", List.of(host3), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web_servers, db_servers, other), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        // Match *_servers
        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play = new Play("Play Wildcard Group", "*_servers", tasks);
        tqm.executePlay(play, inventory, vm, results, false);
        assertTrue(results.containsKey("host1"), "Should match host in web_servers");
        assertTrue(results.containsKey("host2"), "Should match host in db_servers");
        assertFalse(results.containsKey("host3"), "Should NOT match host in other");
    }

    @Test
    void testNumericalAndAlphabeticalRangeExpansion() {
        Host web0 = new Host("web0");
        Host web1 = new Host("web1");
        Host web2 = new Host("web2");
        Host web3 = new Host("web3");
        Host dba = new Host("dba");
        Host dbb = new Host("dbb");
        Host dbc = new Host("dbc");

        Group web = new Group("web", List.of(web0, web1, web2, web3), List.of(), Map.of());
        Group db = new Group("db", List.of(dba, dbb, dbc), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, db), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play = new Play("Play Ranges", "web[0:2],db[a:b]", tasks);
        tqm.executePlay(play, inventory, vm, results, false);

        assertTrue(results.containsKey("web0"), "Should contain web0");
        assertTrue(results.containsKey("web1"), "Should contain web1");
        assertTrue(results.containsKey("web2"), "Should contain web2");
        assertTrue(results.containsKey("dba"), "Should contain dba");
        assertTrue(results.containsKey("dbb"), "Should contain dbb");
        assertFalse(results.containsKey("web3"), "Should NOT contain web3");
        assertFalse(results.containsKey("dbc"), "Should NOT contain dbc");
    }

    @Test
    void testBracketAwareSplitting() {
        Host web1 = new Host("web1");
        Host web2 = new Host("web2");
        Host db = new Host("db");

        Group web = new Group("web", List.of(web1, web2), List.of(), Map.of());
        Group dbGroup = new Group("dbGroup", List.of(db), List.of(), Map.of());
        Group all = new Group("all", List.of(), List.of(web, dbGroup), Map.of());
        Inventory inventory = new Inventory(all);

        ITaskExecutor executor = mock(ITaskExecutor.class);
        when(executor.execute(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any())).thenReturn(TaskResult.success(false, Map.of()));
        TaskQueueManager tqm = new TaskQueueManager(executor, (h, v) -> mock(Connection.class));
        VariableManager vm = new VariableManager(inventory, Map.of());
        List<Task> tasks = List.of(new Task("test_task", "ping", Map.of()));

        // Bracket-aware split on commas inside bracket should not happen
        Map<String, List<TaskResult>> results = new HashMap<>();
        Play play = new Play("Play Split", "web[1:2],db", tasks);
        tqm.executePlay(play, inventory, vm, results, false);

        assertTrue(results.containsKey("web1"), "Should contain web1");
        assertTrue(results.containsKey("web2"), "Should contain web2");
        assertTrue(results.containsKey("db"), "Should contain db");
    }

    @Test
    void testAdvancedRangeExpansions() {
        // 1. Zero padding: [01:05]
        List<String> r1 = org.example.ansible.util.HostPatternParser.expandPattern("web[01:03]");
        assertEquals(List.of("web01", "web02", "web03"), r1);

        // 2. Reverse numerical range: [5:1]
        List<String> r2 = org.example.ansible.util.HostPatternParser.expandPattern("web[3:1]");
        assertEquals(List.of("web3", "web2", "web1"), r2);

        // 3. Reverse alphabetical range: [c:a]
        List<String> r3 = org.example.ansible.util.HostPatternParser.expandPattern("web[c:a]");
        assertEquals(List.of("webc", "webb", "weba"), r3);

        // 4. Cartesian product / Multiple ranges: web[1:2]-[a:b]
        List<String> r4 = org.example.ansible.util.HostPatternParser.expandPattern("web[1:2]-[a:b]");
        assertEquals(List.of("web1-a", "web1-b", "web2-a", "web2-b"), r4);

        // 5. Unmatched bracket / normal string
        List<String> r5 = org.example.ansible.util.HostPatternParser.expandPattern("web[1:3");
        assertEquals(List.of("web[1:3"), r5);

        List<String> r6 = org.example.ansible.util.HostPatternParser.expandPattern("web1:3]");
        assertEquals(List.of("web1:3]"), r6);
    }

    @Test
    void testSplitBracketAware() {
        List<String> s1 = org.example.ansible.util.HostPatternParser.splitBracketAware("web[0:5],db_servers");
        assertEquals(List.of("web[0:5]", "db_servers"), s1);

        List<String> s2 = org.example.ansible.util.HostPatternParser.splitBracketAware("web[0:5]:db_servers");
        assertEquals(List.of("web[0:5]", "db_servers"), s2);

        List<String> s3 = org.example.ansible.util.HostPatternParser.splitBracketAware(null);
        assertTrue(s3.isEmpty());
    }
}
