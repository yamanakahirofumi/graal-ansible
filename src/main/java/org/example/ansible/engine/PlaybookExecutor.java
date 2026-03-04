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
        Map<String, Set<String>> hostNotifications = new HashMap<>();

        for (Task task : play.tasks()) {
            boolean executedOnce = false;
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                if (task.runOnce() && executedOnce) {
                    continue;
                }
                executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications);
                executedOnce = true;
            }
        }

        // Execute handlers
        for (Host host : targetHosts) {
            Set<String> notifiedHandlers = hostNotifications.getOrDefault(host.name(), Collections.emptySet());
            if (notifiedHandlers.isEmpty()) continue;

            for (Task handler : play.handlers()) {
                if (notifiedHandlers.contains(handler.name())) {
                    // Handlers ignore failedHosts check usually, or they only run for hosts that didn't fail earlier?
                    // Ansible runs handlers even if some earlier tasks failed, as long as the host is still in the play.
                    if (failedHosts.contains(host.name())) continue;
                    executeTaskOnHost(play, host, handler, variableManager, results, failedHosts, hostNotifications);
                }
            }
        }
    }

    private void executeTaskOnHost(Play play, Host host, Task task, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications) {
        if (!task.block().isEmpty()) {
            executeBlock(play, host, task, variableManager, results, failedHosts, hostNotifications);
            return;
        }

        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task);
        TaskResult result;

        if (task.loop() != null) {
            result = executeLoopTask(play, host, task, variableManager, allVars);
        } else {
            result = executeSingleTask(play, host, task, allVars, variableManager);
        }

        if (result != null) {
            // Re-evaluate success and changed based on failed_when and changed_when
            result = evaluateResultCustomization(task, result, allVars);

            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(result);

            if (task.register() != null) {
                variableManager.registerVariable(host.name(), task.register(), result.data());
                // Refresh allVars for subsequent steps (though currently not used in this method)
                allVars = variableManager.getAllVariables(play, host, task);
            }

            if (result.changed() && !task.notifications().isEmpty()) {
                hostNotifications.computeIfAbsent(host.name(), k -> new HashSet<>()).addAll(task.notifications());
            }

            if (!result.success() && !isSkipped(result)) {
                if (!task.ignoreErrors()) {
                    failedHosts.add(host.name());
                }
            }
        }
    }

    private void executeBlock(Play play, Host host, Task blockTask, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications) {
        // Evaluate 'when' for the block itself
        Map<String, Object> blockVars = variableManager.getAllVariables(play, host, blockTask);
        if (blockTask.when() != null) {
            List<String> conditions;
            if (blockTask.when() instanceof List<?> list) {
                conditions = list.stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toList());
            } else if (blockTask.when() instanceof String s) {
                conditions = List.of(s);
            } else {
                conditions = List.of(blockTask.when().toString());
            }

            for (String condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(wrapInJinja(condition), blockVars);
                if (!isTrue(conditionResult)) {
                    // Block skipped
                    results.computeIfAbsent(host.name(), k -> new ArrayList<>())
                           .add(new TaskResult(true, false, "Skipped due to block when condition", Map.of("skipped", true)));
                    return;
                }
            }
        }

        boolean blockFailed = false;
        Set<String> blockFailedHosts = new HashSet<>();

        for (Task task : blockTask.block()) {
            if (blockFailedHosts.contains(host.name())) {
                blockFailed = true;
                break;
            }
            executeTaskOnHost(play, host, task, variableManager, results, blockFailedHosts, hostNotifications);
        }

        if (blockFailedHosts.contains(host.name())) {
            blockFailed = true;
        }

        if (blockFailed) {
            for (Task task : blockTask.rescue()) {
                // rescue tasks run even if block failed
                executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications);
            }
        }

        for (Task task : blockTask.always()) {
            executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications);
        }

        if (blockFailed && blockTask.rescue().isEmpty()) {
            failedHosts.add(host.name());
        }
    }

    private TaskResult evaluateResultCustomization(Task task, TaskResult result, Map<String, Object> variables) {
        if (isSkipped(result)) return result;

        boolean success = result.success();
        boolean changed = result.changed();

        Map<String, Object> evalVars = new HashMap<>(variables);
        evalVars.putAll(result.data());

        if (task.failedWhen() != null) {
            List<Object> conditions;
            if (task.failedWhen() instanceof List<?> list) {
                conditions = (List<Object>) list;
            } else {
                conditions = List.of(task.failedWhen());
            }

            for (Object condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(wrapInJinja(condition), evalVars);
                if (isTrue(conditionResult)) {
                    success = false;
                    break;
                }
            }
        }

        if (task.changedWhen() != null) {
            List<Object> conditions;
            if (task.changedWhen() instanceof List<?> list) {
                conditions = (List<Object>) list;
            } else {
                conditions = List.of(task.changedWhen());
            }

            boolean allChanged = true;
            for (Object condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(wrapInJinja(condition), evalVars);
                if (!isTrue(conditionResult)) {
                    allChanged = false;
                    break;
                }
            }
            changed = allChanged;
        }

        if (success == result.success() && changed == result.changed()) {
            return result;
        }

        Map<String, Object> newData = new HashMap<>(result.data());
        newData.put("changed", changed);
        return new TaskResult(success, changed, result.message(), newData);
    }

    private String wrapInJinja(Object expression) {
        if (expression instanceof String s) {
            if (s.contains("{{")) return s;
            return "{{ " + s + " }}";
        }
        return expression.toString();
    }

    private TaskResult executeSingleTask(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager) {
        // Evaluate 'when' condition
        if (task.when() != null) {
            List<String> conditions;
            if (task.when() instanceof List<?> list) {
                conditions = list.stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toList());
            } else if (task.when() instanceof String s) {
                conditions = List.of(s);
            } else {
                conditions = List.of(task.when().toString());
            }

            for (String condition : conditions) {
                Object conditionResult = variableResolver.resolveValue(wrapInJinja(condition), variables);
                if (!isTrue(conditionResult)) {
                    return new TaskResult(true, false, "Skipped due to when condition", Map.of("skipped", true));
                }
            }
        }

        // Variable resolution for task arguments
        Map<String, Object> resolvedArgs = variableResolver.resolve(task.args(), variables);
        String resolvedDelegateTo = null;
        if (task.delegateTo() != null) {
            Object resolved = variableResolver.resolveValue(wrapInJinja(task.delegateTo()), variables);
            resolvedDelegateTo = resolved != null ? resolved.toString() : null;
        }

        Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs, task.vars(), task.when(), task.register(), task.loop(), task.notifications(), task.failedWhen(), task.changedWhen(), task.ignoreErrors(),
                task.until(), task.retries(), task.delay(), resolvedDelegateTo, task.runOnce(), task.block(), task.rescue(), task.always());

        if (task.until() == null) {
            return taskExecutor.execute(resolvedTask);
        }

        // Retry logic
        TaskResult lastResult = null;
        for (int i = 0; i < task.retries(); i++) {
            lastResult = taskExecutor.execute(resolvedTask);

            if (task.register() != null && variableManager != null) {
                variableManager.registerVariable(host.name(), task.register(), lastResult.data());
                variables = variableManager.getAllVariables(play, host, task);
            }

            Map<String, Object> evalVars = new HashMap<>(variables);
            evalVars.putAll(lastResult.data());
            Object untilResult = variableResolver.resolveValue(wrapInJinja(task.until()), evalVars);

            if (isTrue(untilResult)) {
                return lastResult;
            }

            if (i < task.retries() - 1) {
                try {
                    Thread.sleep(task.delay() * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // If we reached here, retries were exhausted and condition never met
        if (lastResult != null && lastResult.success()) {
            // Mark as failed because until condition was not met
            return new TaskResult(false, lastResult.changed(), "Until condition not met after " + task.retries() + " retries", lastResult.data());
        }

        return lastResult;
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

            TaskResult result = executeSingleTask(play, host, task, iterationVars, variableManager);

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
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if (s.isEmpty() || s.isBlank() || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
                return false;
            }
            return true;
        }
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof List<?> l) return !l.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }
}
