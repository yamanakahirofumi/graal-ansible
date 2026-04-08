package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.DefaultConnectionFactory;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.parser.YamlParser;
import org.example.ansible.util.Truthiness;

import java.io.FileInputStream;
import java.io.InputStream;
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
 * TaskQueueManager (TQM) manages the distribution of tasks to worker processes (TaskExecutor)
 * and aggregates results. It operates on the Control Node (管理ノード).
 */
public class TaskQueueManager {

    private final ITaskExecutor taskExecutor;
    private final VariableResolver variableResolver = new VariableResolver();
    private final ConnectionFactory connectionFactory;
    private final Map<String, Connection> connectionCache = new HashMap<>();

    public TaskQueueManager(ITaskExecutor taskExecutor) {
        this(taskExecutor, new DefaultConnectionFactory());
    }

    public TaskQueueManager(ITaskExecutor taskExecutor, ConnectionFactory connectionFactory) {
        this.taskExecutor = taskExecutor;
        this.connectionFactory = connectionFactory;
    }

    /**
     * Executes a single play.
     *
     * @param play             The play to execute.
     * @param inventory        The inventory.
     * @param variableManager  The variable manager.
     * @param results          The accumulated results.
     * @param globalCheckMode  Whether the execution is in global check mode.
     */
    public void executePlay(Play play, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode) {
        executePlay(play, inventory, variableManager, results, globalCheckMode, List.of(), List.of(), null);
    }

    /**
     * Executes a single play with tags and limit filtering.
     *
     * @param play             The play to execute.
     * @param inventory        The inventory.
     * @param variableManager  The variable manager.
     * @param results          The accumulated results.
     * @param globalCheckMode  Whether the execution is in global check mode.
     * @param runTags          The tags to run.
     * @param skipTags         The tags to skip.
     * @param limit            The host limit pattern.
     */
    public void executePlay(Play play, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags, String limit) {
        List<Host> targetHosts = getTargetHosts(play.hosts(), inventory, limit);
        if (targetHosts.isEmpty()) {
            return;
        }
        Set<String> failedHosts = new HashSet<>();
        Map<String, Set<String>> hostNotifications = new HashMap<>();

        try {
            for (Task task : play.tasks()) {
                if (!isTaskToBeExecuted(task, runTags, skipTags)) {
                    for (Host host : targetHosts) {
                        results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                    }
                    continue;
                }
                boolean executedOnce = false;
                for (Host host : targetHosts) {
                    if (failedHosts.contains(host.name())) {
                        continue;
                    }
                    if (task.runOnce() && executedOnce) {
                        continue;
                    }

                    // Initial inherited check mode from Play level
                    Map<String, Object> vars = variableManager.getAllVariables(play, host, task, null);
                    boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);

                    try {
                        Connection connection = getOrCreateConnection(host, vars);
                        executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, playCheckMode, null, null, connection, runTags, skipTags);
                    } catch (UnreachableException e) {
                        if (task.ignoreUnreachable()) {
                            TaskResult unreachableResult = TaskResult.unreachable(e.getMessage());
                            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(unreachableResult);
                        } else {
                            failedHosts.add(host.name());
                        }
                    }
                    executedOnce = true;
                }
            }

            // Execute handlers at the end of the play
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                Map<String, Object> vars = variableManager.getAllVariables(play, host, null, null);
                boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);
                try {
                    Connection connection = getOrCreateConnection(host, vars);
                    flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, playCheckMode, connection, runTags, skipTags);
                } catch (UnreachableException e) {
                    failedHosts.add(host.name());
                }
            }
        } finally {
            closeAllConnections();
        }
    }

    private Connection getOrCreateConnection(Host host, Map<String, Object> variables) {
        return connectionCache.computeIfAbsent(host.name(), k -> {
            Connection conn = connectionFactory.createConnection(host, variables);
            conn.connect();
            return conn;
        });
    }

    private void closeAllConnections() {
        for (Connection conn : connectionCache.values()) {
            try {
                conn.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
        connectionCache.clear();
    }

    private void flushHandlersForHost(Play play, Host host, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Connection connection, List<String> runTags, List<String> skipTags) {
        Set<String> allNotifiedHandlers = new HashSet<>();
        boolean anyNewNotified;
        do {
            anyNewNotified = false;
            Set<String> notifiedInThisCycle = hostNotifications.remove(host.name());
            if (notifiedInThisCycle != null && !notifiedInThisCycle.isEmpty()) {
                for (String handlerName : notifiedInThisCycle) {
                    if (allNotifiedHandlers.add(handlerName)) {
                        for (Task handler : play.handlers()) {
                            if (handlerName.equals(handler.name())) {
                                if (failedHosts.contains(host.name())) continue;
                                if (!isTaskToBeExecuted(handler, runTags, skipTags)) continue;
                                executeTaskOnHost(play, host, handler, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, null, null, connection, runTags, skipTags);
                                anyNewNotified = true;
                                break;
                            }
                        }
                    }
                }
            }
        } while (anyNewNotified);
    }

    private void executeTaskOnHost(Play play, Host host, Task task, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Connection connection, List<String> runTags, List<String> skipTags) {
        if (!task.block().isEmpty()) {
            executeBlock(play, host, task, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, connection, runTags, skipTags);
            return;
        }

        String action = task.action();
        if ("include_tasks".equals(action) || "import_tasks".equals(action)) {
            executeIncludeTasks(play, host, task, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, connection, runTags, skipTags);
            return;
        }

        TaskResult result;
        try {
            result = taskExecutor.execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironment, blockVars, connection, connectionFactory);
        } catch (UnreachableException e) {
            if (task.ignoreUnreachable()) {
                result = TaskResult.unreachable(e.getMessage());
            } else {
                failedHosts.add(host.name());
                return;
            }
        }

        if (result != null) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(result);

            if (result.success() && !result.isSkipped() && "meta".equals(task.action()) && "flush_handlers".equals(task.args().get("_raw_params"))) {
                flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, connection, runTags, skipTags);
            }

            if (task.register() != null) {
                variableManager.registerVariable(host.name(), task.register(), result.data());
            }

            // Handle collected facts or included vars
            if (result.data() != null && result.data().containsKey("ansible_facts")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
                String factHost = host.name();
                if (task.delegateFacts() && result.data().containsKey("_ansible_delegated_host")) {
                    // factHost remains the original host (inventory_hostname)
                } else if (result.data().containsKey("_ansible_delegated_host")) {
                    factHost = result.data().get("_ansible_delegated_host").toString();
                }

                action = task.action();
                if (action.startsWith("ansible.builtin.")) {
                    action = action.substring("ansible.builtin.".length());
                } else if (action.startsWith("ansible.legacy.")) {
                    action = action.substring("ansible.legacy.".length());
                }

                if ("include_vars".equals(action)) {
                    variableManager.addIncludedVars(factHost, facts);
                } else if ("set_fact".equals(action)) {
                    variableManager.addSetFactVars(factHost, facts);
                    // Also add as facts (Level 11) for ansible_facts dictionary compatibility
                    variableManager.addFacts(factHost, facts);
                } else {
                    variableManager.addFacts(factHost, facts);
                }
            }

            if (result.changed() && !task.notifications().isEmpty()) {
                hostNotifications.computeIfAbsent(host.name(), k -> new HashSet<>()).addAll(task.notifications());
            }

            if (!result.success()) {
                if (result.isUnreachable()) {
                    failedHosts.add(host.name());
                } else if (!result.isSkipped() && !task.ignoreErrors()) {
                    failedHosts.add(host.name());
                }
            }
        }
    }

    private void executeBlock(Play play, Host host, Task blockTask, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> inheritedBlockVars, Connection connection, List<String> runTags, List<String> skipTags) {
        Map<String, Object> blockVars = variableManager.getAllVariables(play, host, blockTask, inheritedBlockVars);
        boolean blockCheckMode = variableResolver.resolveCheckMode(blockTask.checkMode(), blockVars, inheritedCheckMode);

        if (!variableResolver.isWhenConditionMet(blockTask.when(), blockVars)) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>())
                    .add(TaskResult.skipped("Skipped due to block when condition"));
            return;
        }

        boolean blockFailed = false;
        Set<String> blockFailedHosts = new HashSet<>();
        Object effectiveBlockEnv = blockTask.environment() != null ? blockTask.environment() : inheritedEnvironment;
        Map<String, Object> combinedBlockVars = new HashMap<>();
        if (inheritedBlockVars != null) combinedBlockVars.putAll(inheritedBlockVars);
        combinedBlockVars.putAll(blockTask.vars());

        for (Task task : blockTask.block()) {
            if (blockFailedHosts.contains(host.name())) {
                blockFailed = true;
                break;
            }
            if (!isTaskToBeExecuted(task, runTags, skipTags)) {
                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                continue;
            }
            executeTaskOnHost(play, host, task, variableManager, results, blockFailedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, connection, runTags, skipTags);
        }

        if (blockFailedHosts.contains(host.name())) {
            blockFailed = true;
        }

        if (blockFailed) {
            for (Task task : blockTask.rescue()) {
                executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, connection, runTags, skipTags);
            }
        }

        for (Task task : blockTask.always()) {
            executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, connection, runTags, skipTags);
        }

        if (blockFailed && blockTask.rescue().isEmpty()) {
            failedHosts.add(host.name());
        }
    }


    private List<Host> getTargetHosts(String pattern, Inventory inventory, String limit) {
        List<Host> hosts = getTargetHosts(pattern, inventory);

        if (limit != null && !limit.isBlank()) {
            Set<String> limitSet = new HashSet<>();
            for (String part : limit.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;

                // Use a flattened list of all hosts in the inventory to check against the limit
                List<Host> allInventoryHosts = getAllHosts(inventory.all());
                if (allInventoryHosts.stream().anyMatch(h -> h.name().equals(trimmed))) {
                    limitSet.add(trimmed);
                } else {
                    Group group = findGroup(inventory.all(), trimmed);
                    if (group != null) {
                        for (Host h : getAllHosts(group)) {
                            limitSet.add(h.name());
                        }
                    }
                }
            }
            hosts = hosts.stream().filter(h -> limitSet.contains(h.name())).toList();
        }

        return hosts;
    }

    private List<Host> getTargetHosts(String pattern, Inventory inventory) {
        if (inventory == null) return List.of();
        if ("all".equals(pattern)) {
            return getAllHosts(inventory.all());
        }

        List<Host> allHosts = getAllHosts(inventory.all());
        List<Host> matchingHosts = allHosts.stream()
                .filter(h -> h.name().equals(pattern))
                .toList();
        if (!matchingHosts.isEmpty()) return matchingHosts;

        Group group = findGroup(inventory.all(), pattern);
        if (group != null) {
            return getAllHosts(group);
        }

        return List.of();
    }

    private boolean isTaskToBeExecuted(Task task, List<String> runTags, List<String> skipTags) {
        List<String> taskTags = task.tags();

        // 1. Handle skip_tags
        if (skipTags != null && !skipTags.isEmpty()) {
            if (taskTags.stream().anyMatch(skipTags::contains)) {
                // Special case: 'always' tag is only skipped if 'always' is in skipTags
                if (!taskTags.contains("always") || skipTags.contains("always")) {
                    return false;
                }
            }
        }

        // 2. Handle 'always' tag (if not skipped)
        if (taskTags.contains("always")) {
            return true;
        }

        // 3. Handle 'never' tag
        if (taskTags.contains("never")) {
            if (runTags == null || !runTags.contains("never")) {
                return false;
            }
        }

        // 4. Handle run_tags
        if (runTags == null || runTags.isEmpty() || runTags.contains("all")) {
            return true;
        }

        return taskTags.stream().anyMatch(runTags::contains);
    }

    private List<Host> getAllHosts(Group group) {
        if (group == null) return List.of();
        List<Host> hosts = new ArrayList<>(group.hosts());
        for (Group child : group.children()) {
            hosts.addAll(getAllHosts(child));
        }
        // Use a set of names to distinct hosts but return actual Host objects
        Map<String, Host> distinctHosts = new HashMap<>();
        for (Host h : hosts) {
            distinctHosts.putIfAbsent(h.name(), h);
        }
        return new ArrayList<>(distinctHosts.values());
    }

    private void executeIncludeTasks(Play play, Host host, Task task, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Connection connection, List<String> runTags, List<String> skipTags) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task, blockVars);

        // Resolve loop if present
        List<?> items = variableResolver.resolveLoopItems(task.loop(), allVars);

        if (items != null) {
            for (Object item : items) {
                Map<String, Object> iterationVars = new HashMap<>(allVars);
                iterationVars.put("item", item);
                if (variableResolver.isWhenConditionMet(task.when(), iterationVars)) {
                    executeIncludeTasksIteration(play, host, task, iterationVars, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, connection, runTags, skipTags);
                } else {
                    results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Included tasks skipped due to when condition"));
                }
            }
        } else {
            if (variableResolver.isWhenConditionMet(task.when(), allVars)) {
                executeIncludeTasksIteration(play, host, task, allVars, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, connection, runTags, skipTags);
            } else {
                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Included tasks skipped due to when condition"));
            }
        }
    }

    private void executeIncludeTasksIteration(Play play, Host host, Task task, Map<String, Object> variables, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Connection connection, List<String> runTags, List<String> skipTags) {
        Map<String, Object> resolvedArgs = variableResolver.resolve(task.args(), variables);
        String file = (String) resolvedArgs.get("file");
        if (file == null) {
            file = (String) resolvedArgs.get("_raw_params");
        }

        if (file == null) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.failure("include_tasks requires a 'file' argument"));
            return;
        }

        Path includePath = variableManager.getBaseDir() != null ? variableManager.getBaseDir().resolve(file) : Path.of(file);
        if (!includePath.toFile().exists()) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.failure("Included file not found: " + includePath));
            return;
        }

        try (InputStream is = new FileInputStream(includePath.toFile())) {
            YamlParser parser = new YamlParser();
            List<Task> includedTasks = parser.parseTasks(is, task.tags());

            Map<String, Object> combinedBlockVars = new HashMap<>();
            if (blockVars != null) combinedBlockVars.putAll(blockVars);
            combinedBlockVars.putAll(task.vars());
            if (variables.containsKey("item")) {
                combinedBlockVars.put("item", variables.get("item"));
            }

            for (Task includedTask : includedTasks) {
                if (failedHosts.contains(host.name())) break;
                executeTaskOnHost(play, host, includedTask, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, combinedBlockVars, connection, runTags, skipTags);
            }
        } catch (Exception e) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.failure("Failed to load included tasks: " + e.getMessage()));
        }
    }

    private Group findGroup(Group root, String name) {
        if (root == null) return null;
        if (root.name().equals(name)) return root;
        for (Group child : root.children()) {
            Group found = findGroup(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
