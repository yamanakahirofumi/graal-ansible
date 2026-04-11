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
}
