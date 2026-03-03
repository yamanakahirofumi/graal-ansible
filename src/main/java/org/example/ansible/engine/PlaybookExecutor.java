package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes a Playbook against an Inventory.
 */
public class PlaybookExecutor {

    private final TaskExecutor taskExecutor;
    private final VariableResolver variableResolver = new VariableResolver();

    public PlaybookExecutor(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * Executes the entire playbook.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory) {
        return execute(playbook, inventory, Map.of(), null);
    }

    /**
     * Executes the entire playbook with extra variables.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @param extraVars Extra variables provided from outside.
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars) {
        return execute(playbook, inventory, extraVars, null);
    }

    /**
     * Executes the entire playbook with extra variables and a base directory for file resolution.
     *
     * @param playbook  The playbook to execute.
     * @param inventory The inventory to use.
     * @param extraVars Extra variables provided from outside.
     * @param baseDir   The base directory for resolving relative paths (e.g., vars_files).
     * @return A map of host names to their execution results for each task.
     */
    public Map<String, List<TaskResult>> execute(Playbook playbook, Inventory inventory, Map<String, Object> extraVars, Path baseDir) {
        Map<String, List<TaskResult>> results = new HashMap<>();
        VariableManager variableManager = new VariableManager(inventory, extraVars, baseDir);

        for (Play play : playbook.plays()) {
            executePlay(play, inventory, variableManager, results);
        }

        return results;
    }

    private void executePlay(Play play, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results) {
        List<Host> targetHosts = getTargetHosts(play.hosts(), inventory);
        Set<String> failedHosts = new HashSet<>();

        for (Task task : play.tasks()) {
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                executeTaskOnHost(play, host, task, variableManager, results, failedHosts);
            }
        }
    }

    private void executeTaskOnHost(Play play, Host host, Task task, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task);
        TaskResult result;

        if (task.loop() != null) {
            result = executeLoopTask(play, host, task, variableManager, allVars);
        } else {
            result = executeSingleTask(play, host, task, allVars);
        }

        if (result != null) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(result);

            if (task.register() != null) {
                variableManager.registerVariable(host.name(), task.register(), result.data());
            }

            if (!result.success() && !isSkipped(result)) {
                failedHosts.add(host.name());
            }
        }
    }

    private TaskResult executeSingleTask(Play play, Host host, Task task, Map<String, Object> variables) {
        // Evaluate 'when' condition
        if (task.when() != null) {
            Object conditionResult = variableResolver.resolveValue("{{ " + task.when() + " }}", variables);
            if (!isTrue(conditionResult)) {
                return new TaskResult(true, false, "Skipped due to when condition", Map.of("skipped", true));
            }
        }

        // Variable resolution for task arguments
        Map<String, Object> resolvedArgs = variableResolver.resolve(task.args(), variables);
        Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs, task.vars(), task.when(), task.register(), task.loop());

        return taskExecutor.execute(resolvedTask);
    }

    private TaskResult executeLoopTask(Play play, Host host, Task task, VariableManager variableManager, Map<String, Object> allVars) {
        Object loopValue = task.loop();
        if (loopValue instanceof String str && str.contains("{{")) {
            loopValue = variableResolver.resolveValue(str, allVars);
        } else if (loopValue instanceof String str) {
            // Handle cases where it's a variable name without {{ }}
            loopValue = variableResolver.resolveValue("{{ " + str + " }}", allVars);
        }

        if (!(loopValue instanceof List<?> items)) {
            // If it's not a list, treat it as a single-item list or skip?
            // Ansible usually expects a list.
            return TaskResult.failure("loop must be a list");
        }

        List<Map<String, Object>> loopResults = new ArrayList<>();
        boolean anyFailed = false;
        boolean anyChanged = false;
        boolean allSkipped = true;

        for (Object item : items) {
            Map<String, Object> iterationVars = new HashMap<>(allVars);
            iterationVars.put("item", item);

            TaskResult result = executeSingleTask(play, host, task, iterationVars);

            Map<String, Object> resultData = new HashMap<>(result.data());
            resultData.put("item", item);
            resultData.put("changed", result.changed());
            resultData.put("failed", !result.success());
            if (isSkipped(result)) {
                resultData.put("skipped", true);
            } else {
                allSkipped = false;
            }

            loopResults.add(resultData);

            if (!result.success() && !isSkipped(result)) anyFailed = true;
            if (result.changed()) anyChanged = true;
        }

        Map<String, Object> finalData = new HashMap<>();
        finalData.put("results", loopResults);
        finalData.put("changed", anyChanged);
        if (allSkipped) {
            finalData.put("skipped", true);
        }

        return new TaskResult(!anyFailed, anyChanged, anyFailed ? "One or more loop items failed" : "OK", finalData);
    }

    private boolean isSkipped(TaskResult result) {
        return Boolean.TRUE.equals(result.data().get("skipped"));
    }

    private List<Host> getTargetHosts(String pattern, Inventory inventory) {
        if ("all".equals(pattern)) {
            return getAllHosts(inventory.all());
        }

        // Simple host/group matching
        // First check if it is a host name
        List<Host> allHosts = getAllHosts(inventory.all());
        List<Host> matchingHosts = allHosts.stream()
                .filter(h -> h.name().equals(pattern))
                .collect(Collectors.toList());
        if (!matchingHosts.isEmpty()) return matchingHosts;

        // Check if it is a group name
        Group group = findGroup(inventory.all(), pattern);
        if (group != null) {
            return getAllHosts(group);
        }

        return Collections.emptyList();
    }

    private List<Host> getAllHosts(Group group) {
        List<Host> hosts = new ArrayList<>(group.hosts());
        for (Group child : group.children()) {
            hosts.addAll(getAllHosts(child));
        }
        return hosts.stream().distinct().collect(Collectors.toList());
    }

    private Group findGroup(Group root, String name) {
        if (root.name().equals(name)) return root;
        for (Group child : root.children()) {
            Group found = findGroup(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private boolean isTrue(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
        }
        if (value instanceof Number n) return n.doubleValue() != 0;
        return value != null;
    }
}
