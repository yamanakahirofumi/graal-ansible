package org.example.ansible.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ExecutionReport encapsulates the execution results of a playbook run.
 * It provides detailed statistics per host and across the entire playbook,
 * as well as filtering capabilities for hosts and task results.
 */
public class ExecutionReport {

    private final Map<String, List<TaskResult>> results;
    private final Map<String, Map<String, Integer>> hostStats;
    private final Map<String, Integer> overallStats;

    public ExecutionReport(Map<String, List<TaskResult>> results) {
        this.results = results != null ? Collections.unmodifiableMap(new HashMap<>(results)) : Collections.emptyMap();
        this.hostStats = Collections.unmodifiableMap(calculateHostStats(this.results));
        this.overallStats = Collections.unmodifiableMap(calculateOverallStats(this.hostStats));
    }

    /**
     * Creates an ExecutionReport from raw execution results map.
     *
     * @param results Map of host names to list of TaskResults.
     * @return ExecutionReport instance.
     */
    public static ExecutionReport of(Map<String, List<TaskResult>> results) {
        return new ExecutionReport(results);
    }

    /**
     * Gets the raw execution results map.
     *
     * @return Map of host names to list of TaskResults.
     */
    public Map<String, List<TaskResult>> getResults() {
        return results;
    }

    /**
     * Gets the detailed statistics per host.
     *
     * @return Map of host names to stats map ("ok", "changed", "unreachable", "failed", "skipped", "total").
     */
    public Map<String, Map<String, Integer>> getHostStats() {
        return hostStats;
    }

    /**
     * Gets overall aggregated statistics across all hosts.
     *
     * @return Aggregated stats map ("total_hosts", "ok", "changed", "unreachable", "failed", "skipped", "total_tasks").
     */
    public Map<String, Integer> getOverallStats() {
        return overallStats;
    }

    /**
     * Checks if the playbook run was overall successful (no host failures or unreachable hosts).
     *
     * @return true if no host had failures or unreachable status.
     */
    public boolean isSuccess() {
        return overallStats.getOrDefault("failed", 0) == 0 && overallStats.getOrDefault("unreachable", 0) == 0;
    }

    /**
     * Gets list of host names that experienced task failure or unreachable connection.
     *
     * @return List of host names.
     */
    public List<String> getFailedHosts() {
        return filterHosts(stats -> stats.getOrDefault("failed", 0) > 0 || stats.getOrDefault("unreachable", 0) > 0);
    }

    /**
     * Gets list of host names that had at least one state change.
     *
     * @return List of host names.
     */
    public List<String> getChangedHosts() {
        return filterHosts(stats -> stats.getOrDefault("changed", 0) > 0);
    }

    /**
     * Gets list of host names where at least one task was skipped.
     *
     * @return List of host names.
     */
    public List<String> getSkippedHosts() {
        return filterHosts(stats -> stats.getOrDefault("skipped", 0) > 0);
    }

    /**
     * Filters host names according to a predicate evaluated against host statistics.
     *
     * @param predicate Predicate taking the host stats map.
     * @return List of matching host names.
     */
    public List<String> filterHosts(Predicate<Map<String, Integer>> predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : hostStats.entrySet()) {
            if (predicate.test(entry.getValue())) {
                matched.add(entry.getKey());
            }
        }
        return matched;
    }

    /**
     * Gets task results for a specific host.
     *
     * @param host Host name.
     * @return List of TaskResults or empty list if host is not present.
     */
    public List<TaskResult> getTaskResultsForHost(String host) {
        return results.getOrDefault(host, Collections.emptyList());
    }

    /**
     * Filters task results across all hosts using a predicate.
     *
     * @param predicate Predicate to filter TaskResult instances.
     * @return Map of host names to filtered list of TaskResults (omitting hosts with no matching tasks).
     */
    public Map<String, List<TaskResult>> filterTaskResults(Predicate<TaskResult> predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        Map<String, List<TaskResult>> filteredMap = new HashMap<>();
        for (Map.Entry<String, List<TaskResult>> entry : results.entrySet()) {
            List<TaskResult> filteredList = new ArrayList<>();
            for (TaskResult res : entry.getValue()) {
                if (predicate.test(res)) {
                    filteredList.add(res);
                }
            }
            if (!filteredList.isEmpty()) {
                filteredMap.put(entry.getKey(), Collections.unmodifiableList(filteredList));
            }
        }
        return Collections.unmodifiableMap(filteredMap);
    }

    /**
     * Gets failed task results across all hosts.
     *
     * @return Map of host names to list of failed TaskResults.
     */
    public Map<String, List<TaskResult>> getFailedTaskResults() {
        return filterTaskResults(res -> !res.success() && !res.isUnreachable());
    }

    /**
     * Gets unreachable task results across all hosts.
     *
     * @return Map of host names to list of unreachable TaskResults.
     */
    public Map<String, List<TaskResult>> getUnreachableTaskResults() {
        return filterTaskResults(TaskResult::isUnreachable);
    }

    /**
     * Gets changed task results across all hosts.
     *
     * @return Map of host names to list of changed TaskResults.
     */
    public Map<String, List<TaskResult>> getChangedTaskResults() {
        return filterTaskResults(TaskResult::changed);
    }

    /**
     * Exports summary data as a structured Map for reporting and serialization.
     *
     * @return Map containing overall stats, host stats, and success status.
     */
    public Map<String, Object> toSummaryMap() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("success", isSuccess());
        summary.put("overall", overallStats);
        summary.put("hosts", hostStats);
        return summary;
    }

    private static Map<String, Map<String, Integer>> calculateHostStats(Map<String, List<TaskResult>> results) {
        Map<String, Map<String, Integer>> stats = new HashMap<>();
        for (Map.Entry<String, List<TaskResult>> entry : results.entrySet()) {
            String host = entry.getKey();
            int ok = 0;
            int changed = 0;
            int unreachable = 0;
            int failed = 0;
            int skipped = 0;

            for (TaskResult res : entry.getValue()) {
                if (res.isUnreachable()) {
                    unreachable++;
                } else if (res.isSkipped()) {
                    skipped++;
                } else if (res.success()) {
                    ok++;
                    if (res.changed()) {
                        changed++;
                    }
                } else {
                    failed++;
                }
            }

            Map<String, Integer> hostMap = new HashMap<>();
            hostMap.put("ok", ok);
            hostMap.put("changed", changed);
            hostMap.put("unreachable", unreachable);
            hostMap.put("failed", failed);
            hostMap.put("skipped", skipped);
            hostMap.put("total", ok + unreachable + failed + skipped);

            stats.put(host, Collections.unmodifiableMap(hostMap));
        }
        return stats;
    }

    private static Map<String, Integer> calculateOverallStats(Map<String, Map<String, Integer>> hostStats) {
        int totalHosts = hostStats.size();
        int totalOk = 0;
        int totalChanged = 0;
        int totalUnreachable = 0;
        int totalFailed = 0;
        int totalSkipped = 0;
        int totalTasks = 0;

        for (Map<String, Integer> hs : hostStats.values()) {
            totalOk += hs.getOrDefault("ok", 0);
            totalChanged += hs.getOrDefault("changed", 0);
            totalUnreachable += hs.getOrDefault("unreachable", 0);
            totalFailed += hs.getOrDefault("failed", 0);
            totalSkipped += hs.getOrDefault("skipped", 0);
            totalTasks += hs.getOrDefault("total", 0);
        }

        Map<String, Integer> overallMap = new HashMap<>();
        overallMap.put("total_hosts", totalHosts);
        overallMap.put("ok", totalOk);
        overallMap.put("changed", totalChanged);
        overallMap.put("unreachable", totalUnreachable);
        overallMap.put("failed", totalFailed);
        overallMap.put("skipped", totalSkipped);
        overallMap.put("total_tasks", totalTasks);

        return overallMap;
    }
}
