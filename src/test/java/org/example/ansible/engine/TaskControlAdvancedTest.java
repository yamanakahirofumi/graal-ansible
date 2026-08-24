package org.example.ansible.engine;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        // block:
        //   - block:
        //       - fail_module:
        //     rescue:
        //       - debug: { msg: "inner rescue" }
        //     always:
        //       - debug: { msg: "inner always" }
        //   - fail_module:
        // rescue:
        //   - debug: { msg: "outer rescue" }
        // always:
        //   - debug: { msg: "outer always" }

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

        // Expected order of results:
        // 1. inner fail (failure)
        // 2. inner rescue (success)
        // 3. inner always (success)
        // 4. outer fail (failure) - executed because innerBlock succeeded after rescue
        // 5. outer rescue (success)
        // 6. outer always (success)

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
        // Task that fails due to failed_when but has ignore_errors: true
        Task task = new Task("ignore fail", "debug", Map.of("msg", "i will fail"), Map.of(), null, "reg", null, List.of(),
                "reg.msg == 'i will fail'", null, true,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        // Next task to prove that execution continues
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
        // Block with when condition that is false
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
        // 1. Task that registers a list
        Task task1 = new Task("register list", "debug", Map.of("items_to_reg", List.of("a", "b", "c")), Map.of(), null, "reg_list", null, List.of(),
                null, null, false, null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        // 2. Task that loops over the registered list and has a when condition
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
        // item 'a' should succeed
        assertTrue((Boolean) iterationResults.get(0).get("failed") == false);
        assertFalse((Boolean) iterationResults.get(0).containsKey("skipped"));

        // item 'b' should be skipped
        assertTrue((Boolean) iterationResults.get(1).get("skipped"));

        // item 'c' should succeed
        assertTrue((Boolean) iterationResults.get(2).get("failed") == false);
        assertFalse((Boolean) iterationResults.get(2).containsKey("skipped"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNestedVariableAccess() {
        // 1. Task that registers a complex structure via loop
        Task task1 = new Task("complex loop", "debug", Map.of("val", "{{ item }}"), Map.of(), null, "complex_reg", List.of("one", "two"), List.of(),
                null, null, false, null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        // 2. Task that accesses nested property of registered variable
        // In Ansible, loop results are in .results list
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
    void testBlockExecutionOnUnreachableWithIgnoreUnreachable() {
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
    void testBlockExecutionOnBecomeFailureWithRescueAndAlways() {
        Task becomeTask = new Task("become task", "become_check_module", Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                true, "sudo", "root", null, null, null);

        Task rescueTask = new Task("rescue become", "debug", Map.of("msg", "rescued become failure"));
        Task alwaysTask = new Task("always become", "debug", Map.of("msg", "always become executed"));

        Task block = new Task("block with become failure", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(becomeTask), List.of(rescueTask), List.of(alwaysTask),
                null, null, null, null, null, null);

        Play play = new Play("Become Failure Block Play", "all", List.of(block));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(3, hostResults.size());
        assertFalse(hostResults.get(0).success());
        assertTrue(hostResults.get(1).success());
        assertEquals("rescued become failure", hostResults.get(1).data().get("msg"));
        assertTrue(hostResults.get(2).success());
        assertEquals("always become executed", hostResults.get(2).data().get("msg"));
    }

    @Test
    void testBlockExecutionOnNestedUnreachableFailure() {
        Task innerUnreachable = new Task("inner unreachable", "unreachable_module", Map.of());
        Task innerRescue = new Task("inner rescue", "debug", Map.of("msg", "inner rescue"));
        Task innerAlways = new Task("inner always", "debug", Map.of("msg", "inner always"));

        Task innerBlock = new Task("inner block", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerUnreachable), List.of(innerRescue), List.of(innerAlways),
                null, null, null, null, null, null);

        Task outerNext = new Task("outer next", "debug", Map.of("msg", "outer next"));
        Task outerRescue = new Task("outer rescue", "debug", Map.of("msg", "outer rescue"));
        Task outerAlways = new Task("outer always", "debug", Map.of("msg", "outer always"));

        Task outerBlock = new Task("outer block", null, Map.of(), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(innerBlock, outerNext), List.of(outerRescue), List.of(outerAlways),
                null, null, null, null, null, null);

        Play play = new Play("Nested Unreachable Play", "all", List.of(outerBlock));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(5, hostResults.size());
        assertTrue(hostResults.get(0).isUnreachable());
        assertTrue(hostResults.get(1).success());
        assertEquals("inner rescue", hostResults.get(1).data().get("msg"));
        assertTrue(hostResults.get(2).success());
        assertEquals("inner always", hostResults.get(2).data().get("msg"));
        assertTrue(hostResults.get(3).success());
        assertEquals("outer next", hostResults.get(3).data().get("msg"));
        assertTrue(hostResults.get(4).success());
        assertEquals("outer always", hostResults.get(4).data().get("msg"));
    }

    @Test
    void testUntilRetrySuccess() {
        // Register a counter module that increments counter on each call
        final int[] attempts = {0};
        taskExecutor.registerModule("counter_module", (args, become, context) -> {
            attempts[0]++;
            return TaskResult.success(false, Map.of("count", attempts[0]));
        });

        // Task retries until count == 3
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
    void testUntilRetryExceedMaxRetries() {
        // Module that always returns count 1
        taskExecutor.registerModule("static_counter", (args, become, context) ->
                TaskResult.success(false, Map.of("count", 1)));

        // Task retries until count == 5, but retries = 3
        Task task = new Task("retry fail", "static_counter", Map.of(), Map.of(), null, "reg_static", null, List.of(),
                null, null, false, "reg_static.count == 5", 3, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("Retry Failure Play", "all", List.of(task));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        assertEquals(1, hostResults.size());
        assertFalse(hostResults.get(0).success(), "Should fail because until condition was not met after 3 retries");
        assertEquals("Until condition not met after 3 retries", hostResults.get(0).message());
        assertEquals(3, hostResults.get(0).data().get("attempts"));
    }

    @Test
    void testFailedWhenAndChangedWhenListAndEvaluation() {
        // Register a custom module returning status code 200 and custom flag
        taskExecutor.registerModule("custom_status", (args, become, context) ->
                TaskResult.success(false, Map.of("status", 200, "flag", "active")));

        // Task 1: failed_when as list where both conditions are true -> task fails
        Task failedTask = new Task("list failed when", "custom_status", Map.of(), Map.of(), null, null, null, List.of(),
                List.of("status == 200", "flag == 'active'"), null, false, null, 1, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        // Task 2: failed_when as list where one condition is false -> task succeeds
        Task succeedTask = new Task("list failed when partial", "custom_status", Map.of(), Map.of(), null, null, null, List.of(),
                List.of("status == 200", "flag == 'inactive'"), null, false, null, 1, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        // Task 3: changed_when as list where both conditions are true -> changed = true
        Task changedTask = new Task("list changed when", "custom_status", Map.of(), Map.of(), null, null, null, List.of(),
                null, List.of("status == 200", "flag == 'active'"), false, null, 1, 0, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null);

        Play play = new Play("Customization List Play", "all", List.of(failedTask, succeedTask, changedTask));
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get("localhost");
        // failedTask fails, stopping play for localhost
        assertEquals(1, hostResults.size());
        assertFalse(hostResults.get(0).success(), "failed_when list with all true conditions should cause failure");

        // Execute succeedTask and changedTask in a separate play
        Play play2 = new Play("Customization List Play 2", "all", List.of(succeedTask, changedTask));
        Map<String, List<TaskResult>> results2 = new HashMap<>();

        tqm.executePlay(play2, inventory, vm, results2, false);

        List<TaskResult> hostResults2 = results2.get("localhost");
        assertEquals(2, hostResults2.size());
        assertTrue(hostResults2.get(0).success(), "failed_when list with one false condition should NOT cause failure");
        assertTrue(hostResults2.get(1).changed(), "changed_when list with all true conditions should set changed = true");
    }
}
