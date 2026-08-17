package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.util.OSHandler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionReportTest {

    @Test
    void testStatsCalculation() {
        TaskResult resOk = TaskResult.success(false, Map.of("msg", "ok"));
        TaskResult resChanged = TaskResult.success(true, Map.of("msg", "changed"));
        TaskResult resFailed = TaskResult.failure("failed");
        TaskResult resSkipped = TaskResult.skipped("skipped");
        TaskResult resUnreachable = TaskResult.unreachable("unreachable");

        Map<String, List<TaskResult>> results = Map.of(
                "host1", List.of(resOk, resChanged),
                "host2", List.of(resFailed, resSkipped),
                "host3", List.of(resUnreachable)
        );

        ExecutionReport report = ExecutionReport.of(results);

        assertFalse(report.isSuccess(), "Overall report should be false due to failed and unreachable hosts");

        // Host stats assertions
        Map<String, Map<String, Integer>> hostStats = report.getHostStats();

        // host1: 1 ok, 1 changed
        Map<String, Integer> host1Stats = hostStats.get("host1");
        assertEquals(2, host1Stats.get("ok"));
        assertEquals(1, host1Stats.get("changed"));
        assertEquals(0, host1Stats.get("failed"));
        assertEquals(0, host1Stats.get("unreachable"));
        assertEquals(0, host1Stats.get("skipped"));
        assertEquals(2, host1Stats.get("total"));

        // host2: 1 failed, 1 skipped
        Map<String, Integer> host2Stats = hostStats.get("host2");
        assertEquals(0, host2Stats.get("ok"));
        assertEquals(0, host2Stats.get("changed"));
        assertEquals(1, host2Stats.get("failed"));
        assertEquals(0, host2Stats.get("unreachable"));
        assertEquals(1, host2Stats.get("skipped"));
        assertEquals(2, host2Stats.get("total"));

        // host3: 1 unreachable
        Map<String, Integer> host3Stats = hostStats.get("host3");
        assertEquals(0, host3Stats.get("ok"));
        assertEquals(1, host3Stats.get("unreachable"));
        assertEquals(1, host3Stats.get("total"));

        // Overall stats assertions
        Map<String, Integer> overall = report.getOverallStats();
        assertEquals(3, overall.get("total_hosts"));
        assertEquals(2, overall.get("ok"));
        assertEquals(1, overall.get("changed"));
        assertEquals(1, overall.get("failed"));
        assertEquals(1, overall.get("unreachable"));
        assertEquals(1, overall.get("skipped"));
        assertEquals(5, overall.get("total_tasks"));
    }

    @Test
    void testHostFiltering() {
        TaskResult resOk = TaskResult.success(false, Map.of());
        TaskResult resChanged = TaskResult.success(true, Map.of());
        TaskResult resFailed = TaskResult.failure("failed");
        TaskResult resSkipped = TaskResult.skipped("skipped");

        Map<String, List<TaskResult>> results = Map.of(
                "host1", List.of(resOk),
                "host2", List.of(resChanged),
                "host3", List.of(resFailed),
                "host4", List.of(resSkipped)
        );

        ExecutionReport report = new ExecutionReport(results);

        List<String> failedHosts = report.getFailedHosts();
        assertEquals(List.of("host3"), failedHosts);

        List<String> changedHosts = report.getChangedHosts();
        assertEquals(List.of("host2"), changedHosts);

        List<String> skippedHosts = report.getSkippedHosts();
        assertEquals(List.of("host4"), skippedHosts);

        // Custom predicate filter: hosts with total tasks == 1
        List<String> matchedHosts = report.filterHosts(stats -> stats.get("total") == 1);
        assertEquals(4, matchedHosts.size());
    }

    @Test
    void testTaskResultFiltering() {
        TaskResult res1 = TaskResult.success(true, Map.of("key", "val1"));
        TaskResult res2 = TaskResult.failure("error in task");
        TaskResult res3 = TaskResult.unreachable("host unreachable");

        Map<String, List<TaskResult>> results = Map.of(
                "host1", List.of(res1, res2),
                "host2", List.of(res3)
        );

        ExecutionReport report = ExecutionReport.of(results);

        assertEquals(List.of(res1, res2), report.getTaskResultsForHost("host1"));
        assertTrue(report.getTaskResultsForHost("unknown_host").isEmpty());

        Map<String, List<TaskResult>> failedTasks = report.getFailedTaskResults();
        assertEquals(1, failedTasks.size());
        assertEquals(List.of(res2), failedTasks.get("host1"));

        Map<String, List<TaskResult>> unreachableTasks = report.getUnreachableTaskResults();
        assertEquals(1, unreachableTasks.size());
        assertEquals(List.of(res3), unreachableTasks.get("host2"));

        Map<String, List<TaskResult>> changedTasks = report.getChangedTaskResults();
        assertEquals(1, changedTasks.size());
        assertEquals(List.of(res1), changedTasks.get("host1"));

        // Predicate filter: tasks with message containing "task"
        Map<String, List<TaskResult>> customFiltered = report.filterTaskResults(res -> res.message() != null && res.message().contains("task"));
        assertEquals(1, customFiltered.size());
        assertEquals(List.of(res2), customFiltered.get("host1"));
    }

    @Test
    void testToSummaryMap() {
        TaskResult resOk = TaskResult.success(false, Map.of());
        Map<String, List<TaskResult>> results = Map.of("localhost", List.of(resOk));

        ExecutionReport report = ExecutionReport.of(results);
        Map<String, Object> summary = report.toSummaryMap();

        assertEquals(true, summary.get("success"));
        assertNotNull(summary.get("overall"));
        assertNotNull(summary.get("hosts"));
    }

    @Test
    void testPlaybookExecutorIntegration() {
        ITaskExecutor mockTaskExecutor = new ITaskExecutor() {
            @Override
            public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, Connection connection, ConnectionFactory connectionFactory) {
                return TaskResult.success(true, Map.of("msg", "done"));
            }

            @Override
            public TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, List<Object> inheritedEnvironments, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, Connection connection, ConnectionFactory connectionFactory) {
                return TaskResult.success(true, Map.of("msg", "done"));
            }

            @Override
            public TaskResult execute(Task task, BecomeContext becomeContext, Map<String, String> environment) {
                return TaskResult.success(true, Map.of());
            }

            @Override
            public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, Map<String, String> environment) {
                return TaskResult.success(true, Map.of());
            }

            @Override
            public Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars) {
                return Map.of();
            }

            @Override public OSHandler getOsHandler() { return null; }
            @Override public VariableResolver getVariableResolver() { return new VariableResolver(); }
            @Override public VariableManager getVariableManager() { return null; }
            @Override public String resolveLocalPath(String path) { return path; }
            @Override public void close() {}
        };

        PlaybookExecutor executor = new PlaybookExecutor(mockTaskExecutor, (host, vars) -> new org.example.ansible.connection.LocalConnection());
        Host host = new Host("node1");
        Group allGroup = new Group("all", List.of(host), List.of(), Map.of());
        Inventory inventory = new Inventory(allGroup);
        Task task = new Task("test_task", "debug", Map.of());
        Play play = new Play("test_play", "all", List.of(task));
        Playbook playbook = new Playbook(List.of(play));

        ExecutionReport report = executor.executeAndReport(playbook, inventory);

        assertTrue(report.isSuccess());
        assertEquals(1, report.getOverallStats().get("total_hosts"));
        assertEquals(1, report.getOverallStats().get("ok"));
        assertEquals(1, report.getOverallStats().get("changed"));
        assertEquals(List.of("node1"), report.getChangedHosts());
    }
}
