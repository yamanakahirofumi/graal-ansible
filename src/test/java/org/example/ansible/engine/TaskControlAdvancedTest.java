package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Advanced integration tests for task control flow, as planned in Test-Expansion-Strategy.md.
 */
class TaskControlAdvancedTest {

    private TaskExecutor taskExecutor;
    private TaskQueueManager tqm;
    private Inventory inventory;
    private VariableManager vm;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        // Register debug for testing
        taskExecutor.registerModule("debug", (args, become, context) -> {
            Map<String, Object> data = new HashMap<>(args);
            if (!data.containsKey("msg")) {
                data.put("msg", "hello");
            }
            return TaskResult.success(false, data);
        });
        // Register a module that can fail
        taskExecutor.registerModule("fail_module", (args, become, context) ->
            TaskResult.failure("Intentional failure"));

        // Register an unreachable module
        taskExecutor.registerModule("unreachable_module", (args, become, context) -> {
            throw new UnreachableException("Connection refused");
        });

        // Register a module checking privilege escalation (become)
        taskExecutor.registerModule("become_check_module", (args, become, context) -> {
            if (become != null && become.become()) {
                return TaskResult.failure("Privilege escalation failed");
            }
            return TaskResult.success(Map.of("msg", "no become"));
        });

        tqm = new TaskQueueManager(taskExecutor, (host, vars) -> new LocalConnection());
        Host host = new Host("localhost");
        inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        vm = new VariableManager(inventory, Map.of(), tempDir);
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testNestedBlockRescueAlways() {
        Task innerFail = new Task("inner fail", "fail_module", Map.of());
        Task innerRescue = new Task("inner rescue", "debug", Map.of("msg", "inner rescue"));
        Task innerAlways = new Task("inner always", "debug", Map.of("msg", "inner always"));

        Task innerBlock = new Task("inner block", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerFail), List.of(innerRescue), List.of(innerAlways),
                null, null, null, null, null, null);

        Task outerFail = new Task("outer fail", "fail_module", Map.of());
        Task outerRescue = new Task("outer rescue", "debug", Map.of("msg", "outer rescue"));
        Task outerAlways = new Task("outer always", "debug", Map.of("msg", "outer always"));

        Task outerBlock = new Task("outer block", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerBlock, outerFail), List.of(outerRescue), List.of(outerAlways),
                null, null, null, null, null, null);

        Play play = new Play("Nested Block Play", "all", List.of(outerBlock));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");

        assertEquals(6, hostResults.size());
        assertFalse(hostResults.get(0).success(), "Inner fail should fail");
        assertTrue(hostResults.get(1).success(), "Inner rescue should succeed");
        assertEquals("inner rescue", hostResults.get(1).data().get("msg"));
        assertTrue(hostResults.get(2).success(), "Inner always should succeed");
        assertEquals("inner always", hostResults.get(2).data().get("msg"));
        assertFalse(hostResults.get(3).success(), "Outer fail should fail");
        assertTrue(hostResults.get(4).success(), "Outer rescue should succeed");
        assertEquals("outer rescue", hostResults.get(4).data().get("msg"));
        assertTrue(hostResults.get(5).success(), "Outer always should succeed");
        assertEquals("outer always", hostResults.get(5).data().get("msg"));
    }

    @Test
    void testIgnoreErrorsWithFailedWhen() {
        Task task = new Task("ignore fail", "debug", Map.of("msg", "i will fail"), Map.of(), null, "reg", null, List.of(),
                "reg.msg == 'i will fail'", null, true,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Task nextTask = new Task("next", "debug", Map.of("msg", "i am next"));

        Play play = new Play("Ignore Errors Play", "all", List.of(task, nextTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());
        assertFalse(hostResults.get(0).success(), "First task should be failed (in data) but ignored");
        assertTrue(hostResults.get(1).success(), "Second task should execute because first failure was ignored");
        assertEquals("i am next", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testBlockWhenCondition() {
        Task taskInBlock = new Task("inside", "debug", Map.of("msg", "should not run"));
        Task block = new Task("block with when", null, Map.of(), Map.of(), "1 == 2", null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(taskInBlock), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("Block When Play", "all", List.of(block));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertTrue(hostResults.get(0).isSkipped(), "Block itself should be reported as skipped");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDynamicVariableResolutionInLoopAndWhen() {
        Task task1 = new Task("register list", "debug", Map.of("items_to_reg", List.of("a", "b", "c")), Map.of(), null, "reg_list", null, List.of(),
                null, null, false, null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Task task2 = new Task("loop over reg", "debug", Map.of("msg", "item is {{ item }}"), Map.of(), "item != 'b'", "reg_loop", "{{ reg_list.items_to_reg }}", List.of(),
                null, null, false, null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("Dynamic Var Play", "all", List.of(task1, task2));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());

        TaskResult loopResult = hostResults.get(1);
        Map<String, Object> data = loopResult.data();
        List<Map<String, Object>> iterationResults = (List<Map<String, Object>>) data.get("results");

        assertEquals(3, iterationResults.size());
        assertTrue((Boolean) iterationResults.get(0).get("failed") == false);
        assertFalse((Boolean) iterationResults.get(0).containsKey("skipped"));

        assertTrue((Boolean) iterationResults.get(1).get("skipped"));

        assertTrue((Boolean) iterationResults.get(2).get("failed") == false);
        assertFalse((Boolean) iterationResults.get(2).containsKey("skipped"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNestedVariableAccess() {
        Task task1 = new Task("complex loop", "debug", Map.of("val", "{{ item }}"), Map.of(), null, "complex_reg", List.of("one", "two"), List.of(),
                null, null, false, null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Task task2 = new Task("access nested", "debug", Map.of("msg", "first was {{ complex_reg.results[0].item }}"));

        Play play = new Play("Nested Access Play", "all", List.of(task1, task2));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(2, hostResults.size());
        assertEquals("first was one", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testBlockExecutionOnUnreachableWithRescueAndAlways() {
        Task unreachableTask = new Task("unreachable in block", "unreachable_module", Map.of());
        Task rescueTask = new Task("rescue task", "debug", Map.of("msg", "rescued unreachable"));
        Task alwaysTask = new Task("always task", "debug", Map.of("msg", "always executed"));

        Task block = new Task("block with unreachable", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(unreachableTask), List.of(rescueTask), List.of(alwaysTask),
                null, null, null, null, null, null);

        Task postBlockTask = new Task("post block task", "debug", Map.of("msg", "post block"));

        Play play = new Play("Unreachable Block Play", "all", List.of(block, postBlockTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(4, hostResults.size());
        assertTrue(hostResults.get(0).isUnreachable());
        assertTrue(hostResults.get(1).success());
        assertEquals("rescued unreachable", hostResults.get(1).data().get("msg"));
        assertTrue(hostResults.get(2).success());
        assertEquals("always executed", hostResults.get(2).data().get("msg"));
        assertTrue(hostResults.get(3).success());
        assertEquals("post block", hostResults.get(3).data().get("msg"));
    }

    @Test
    void testBlockExecutionOnIgnoreUnreachable() {
        Task unreachableTask = new Task("ignore unreachable task", "unreachable_module", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, true, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Task nextBlockTask = new Task("next in block", "debug", Map.of("msg", "next in block"));
        Task rescueTask = new Task("rescue task", "debug", Map.of("msg", "rescue task"));
        Task alwaysTask = new Task("always task", "debug", Map.of("msg", "always task"));

        Task block = new Task("block with ignore unreachable", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(unreachableTask, nextBlockTask), List.of(rescueTask), List.of(alwaysTask),
                null, null, null, null, null, null);

        Play play = new Play("Ignore Unreachable Block Play", "all", List.of(block));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(3, hostResults.size());
        assertTrue(hostResults.get(0).isUnreachable());
        assertTrue(hostResults.get(1).success());
        assertEquals("next in block", hostResults.get(1).data().get("msg"));
        assertTrue(hostResults.get(2).success());
        assertEquals("always task", hostResults.get(2).data().get("msg"));
    }

    @Test
    void testUntilRetrySuccess() {
        final int[] attempts = {0};
        taskExecutor.registerModule("counter_module", (args, become, context) -> {
            attempts[0]++;
            return TaskResult.success(false, Map.of("count", attempts[0]));
        });

        Task task = new Task("retry counter", "counter_module", Map.of(), Map.of(), null, "reg_count", null, List.of(),
                null, null, false, "reg_count.count == 3", 5, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("Retry Success Play", "all", List.of(task));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertTrue(hostResults.get(0).success());
        assertEquals(3, attempts[0]);
        assertEquals(3, hostResults.get(0).data().get("attempts"));
    }

    @Test
    void testThrottleWithTemplateResolution() throws Exception {
        List<Host> hostList = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hostList.add(new Host("host" + i));
        }
        Inventory multiHostInventory = new Inventory(new Group("all", hostList, Collections.emptyList(), Collections.emptyMap()));

        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        AtomicInteger activeExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrentExecutions = new AtomicInteger(0);

        when(connectionFactory.createConnection(any(Host.class), any())).thenAnswer(invocation -> {
            Connection conn = mock(Connection.class);
            when(conn.execCommand(any(), any(), any())).thenAnswer(execInvocation -> {
                int current = activeExecutions.incrementAndGet();
                synchronized (maxConcurrentExecutions) {
                    if (current > maxConcurrentExecutions.get()) {
                        maxConcurrentExecutions.set(current);
                    }
                }
                Thread.sleep(50);
                activeExecutions.decrementAndGet();
                return new ConnectionResult("ok", "", 0);
            });
            return conn;
        });

        TaskQueueManager multiTqm = new TaskQueueManager(taskExecutor, connectionFactory);
        multiTqm.setForks(6);

        Task task = new Task(
                "throttle template task",
                "command",
                Map.of("_raw_params", "echo hi"),
                Map.of(), null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null,
                null,
                "{{ max_limit }}",
                0, 10
        );

        Play play = new Play("throttle template play", "all", List.of(task), Map.of("max_limit", 2), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "free", null, null, null);

        VariableManager multiVm = new VariableManager(multiHostInventory, Map.of("max_limit", 2));
        Map<String, List<TaskResult>> results = new ConcurrentHashMap<>();

        multiTqm.executePlay(play, multiHostInventory, multiVm, results, false);

        assertTrue(maxConcurrentExecutions.get() <= 2, "Max concurrent executions was " + maxConcurrentExecutions.get() + ", expected <= 2");
        assertTrue(maxConcurrentExecutions.get() > 0);
    }
}
