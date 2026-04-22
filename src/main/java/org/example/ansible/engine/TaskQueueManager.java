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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
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
            for (Role role : play.roles()) {
                executeRole(play, role, targetHosts, inventory, variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
            }

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
                    Map<String, Object> vars = variableManager.getAllVariables(play, host, task, null, null, null);
                    boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);

                    try {
                        Connection connection = getOrCreateConnection(host, vars);
                        executeTaskOnHost(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, playCheckMode, null, null, null, null, connection, runTags, skipTags);
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
                Map<String, Object> vars = variableManager.getAllVariables(play, host, null, null, null, null);
                boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);
                try {
                    Connection connection = getOrCreateConnection(host, vars);
                    flushHandlersForHost(play, host, inventory, variableManager, results, failedHosts, hostNotifications, playCheckMode, connection, runTags, skipTags);
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

    private void flushHandlersForHost(Play play, Host host, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Connection connection, List<String> runTags, List<String> skipTags) {
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
                                executeTaskOnHost(play, host, handler, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, null, null, null, null, connection, runTags, skipTags);
                                anyNewNotified = true;
                                break;
                            }
                        }
                    }
                }
            }
        } while (anyNewNotified);
    }

    private void executeTaskOnHost(Play play, Host host, Task task, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Map<String, Object> roleParams, Map<String, Object> includeParams, Connection connection, List<String> runTags, List<String> skipTags) {
        if (!task.block().isEmpty()) {
            executeBlock(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, roleParams, includeParams, connection, runTags, skipTags);
            return;
        }

        String action = task.action();
        if ("include_tasks".equals(action) || "import_tasks".equals(action)) {
            executeIncludeTasks(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, roleParams, includeParams, connection, runTags, skipTags);
            return;
        }

        TaskResult result;
        try {
            result = taskExecutor.execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironment, blockVars, roleParams, includeParams, connection, connectionFactory);
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
                flushHandlersForHost(play, host, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, connection, runTags, skipTags);
            }

            if (task.register() != null) {
                Map<String, Object> registerData = new HashMap<>(result.data());
                registerData.put("changed", result.changed());
                registerData.put("failed", !result.success());
                if (result.isSkipped()) {
                    registerData.put("skipped", true);
                }
                variableManager.registerVariable(host.name(), task.register(), registerData);
            }

            String normalizedAction = task.action();
            if (normalizedAction.startsWith("ansible.builtin.")) {
                normalizedAction = normalizedAction.substring("ansible.builtin.".length());
            } else if (normalizedAction.startsWith("ansible.legacy.")) {
                normalizedAction = normalizedAction.substring("ansible.legacy.".length());
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

                if ("include_vars".equals(normalizedAction)) {
                    variableManager.addIncludedVars(factHost, facts);
                } else if ("set_fact".equals(normalizedAction)) {
                    variableManager.addSetFactVars(factHost, facts);
                    // Also add as facts (Level 11) for ansible_facts dictionary compatibility
                    variableManager.addFacts(factHost, facts);
                } else {
                    variableManager.addFacts(factHost, facts);
                }
            }

            // Handle dynamic inventory updates
            if (result.success() && "add_host".equals(normalizedAction)) {
                processAddHost(result, inventory);
            } else if (result.success() && "group_by".equals(normalizedAction)) {
                processGroupBy(result, inventory, host.name());
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

    private void executeBlock(Play play, Host host, Task blockTask, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> inheritedBlockVars, Map<String, Object> roleParams, Map<String, Object> includeParams, Connection connection, List<String> runTags, List<String> skipTags) {
        Map<String, Object> blockVars = variableManager.getAllVariables(play, host, blockTask, inheritedBlockVars, roleParams, includeParams);
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
            executeTaskOnHost(play, host, task, inventory, variableManager, results, blockFailedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, roleParams, includeParams, connection, runTags, skipTags);
        }

        if (blockFailedHosts.contains(host.name())) {
            blockFailed = true;
        }

        if (blockFailed) {
            for (Task task : blockTask.rescue()) {
                executeTaskOnHost(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, roleParams, includeParams, connection, runTags, skipTags);
            }
        }

        for (Task task : blockTask.always()) {
            executeTaskOnHost(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, combinedBlockVars, roleParams, includeParams, connection, runTags, skipTags);
        }

        if (blockFailed && blockTask.rescue().isEmpty()) {
            failedHosts.add(host.name());
        }
    }


    private List<Host> getTargetHosts(String pattern, Inventory inventory, String limit) {
        List<Host> hosts = getTargetHosts(pattern, inventory);

        if (limit != null && !limit.isBlank()) {
            List<Host> limitHosts = getTargetHosts(limit, inventory);
            Set<String> limitNames = limitHosts.stream().map(Host::name).collect(Collectors.toSet());
            hosts = hosts.stream().filter(h -> limitNames.contains(h.name())).toList();
        }

        return hosts;
    }

    private List<Host> getTargetHosts(String pattern, Inventory inventory) {
        if (pattern == null || pattern.isBlank() || inventory == null) {
            return List.of();
        }

        Set<String> matchedHostNames = new HashSet<>();
        List<Host> allHosts = getAllHosts(inventory.all());

        for (String subPattern : pattern.split("[,:]")) {
            String trimmed = subPattern.trim();
            if (trimmed.isEmpty()) continue;

            if ("all".equals(trimmed)) {
                for (Host h : allHosts) matchedHostNames.add(h.name());
                continue;
            }

            if (trimmed.contains("*")) {
                String regex = trimmed.replace(".", "\\.").replace("*", ".*");
                // Match hosts
                for (Host h : allHosts) {
                    if (h.name().matches(regex)) {
                        matchedHostNames.add(h.name());
                    }
                }
                // Match groups
                matchGroupsByWildcard(inventory.all(), regex, matchedHostNames);
            } else {
                // Exact match
                boolean foundAsHost = false;
                for (Host h : allHosts) {
                    if (h.name().equals(trimmed)) {
                        matchedHostNames.add(h.name());
                        foundAsHost = true;
                    }
                }
                if (!foundAsHost) {
                    Group group = findGroup(inventory.all(), trimmed);
                    if (group != null) {
                        for (Host h : getAllHosts(group)) {
                            matchedHostNames.add(h.name());
                        }
                    }
                }
            }
        }

        List<Host> result = new ArrayList<>();
        for (Host h : allHosts) {
            if (matchedHostNames.contains(h.name())) {
                result.add(h);
            }
        }
        return result;
    }

    private void matchGroupsByWildcard(Group group, String regex, Set<String> matchedHostNames) {
        if (group.name().matches(regex)) {
            for (Host h : getAllHosts(group)) {
                matchedHostNames.add(h.name());
            }
        }
        for (Group child : group.children()) {
            matchGroupsByWildcard(child, regex, matchedHostNames);
        }
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

    private void executeIncludeTasks(Play play, Host host, Task task, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Map<String, Object> roleParams, Map<String, Object> includeParams, Connection connection, List<String> runTags, List<String> skipTags) {
        Map<String, Object> allVars = variableManager.getAllVariables(play, host, task, blockVars, roleParams, includeParams);

        // Resolve loop if present
        List<?> items = variableResolver.resolveLoopItems(task.loop(), allVars);

        if (items != null) {
            for (Object item : items) {
                Map<String, Object> iterationVars = new HashMap<>(allVars);
                iterationVars.put("item", item);
                if (variableResolver.isWhenConditionMet(task.when(), iterationVars)) {
                    executeIncludeTasksIteration(play, host, task, iterationVars, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, roleParams, includeParams, connection, runTags, skipTags);
                } else {
                    results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Included tasks skipped due to when condition"));
                }
            }
        } else {
            if (variableResolver.isWhenConditionMet(task.when(), allVars)) {
                executeIncludeTasksIteration(play, host, task, allVars, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, blockVars, roleParams, includeParams, connection, runTags, skipTags);
            } else {
                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Included tasks skipped due to when condition"));
            }
        }
    }

    private void executeIncludeTasksIteration(Play play, Host host, Task task, Map<String, Object> variables, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Map<String, Object> roleParams, Map<String, Object> inheritedIncludeParams, Connection connection, List<String> runTags, List<String> skipTags) {
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

            // Extract include parameters (Level 21)
            // They are: all task args except file and _raw_params, plus the task's vars
            Map<String, Object> includeParams = new HashMap<>();
            if (inheritedIncludeParams != null) {
                includeParams.putAll(inheritedIncludeParams);
            }
            includeParams.putAll(resolvedArgs);
            includeParams.remove("file");
            includeParams.remove("_raw_params");
            includeParams.putAll(task.vars());
            if (variables.containsKey("item")) {
                includeParams.put("item", variables.get("item"));
            }

            for (Task includedTask : includedTasks) {
                if (failedHosts.contains(host.name())) break;
                executeTaskOnHost(play, host, includedTask, inventory, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, combinedBlockVars, roleParams, includeParams, connection, runTags, skipTags);
            }
        } catch (Exception e) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.failure("Failed to load included tasks: " + e.getMessage()));
        }
    }

    private void executeRole(Play play, Role role, List<Host> targetHosts, Inventory inventory, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean globalCheckMode, List<String> runTags, List<String> skipTags) {
        Path playbookDir = variableManager.getBaseDir();
        if (playbookDir == null) {
            playbookDir = Path.of(".");
        }
        Path roleDir = playbookDir.resolve("roles").resolve(role.name());
        if (!Files.exists(roleDir)) {
            // Check in current directory too, as a fallback or if roles is a sibling
            roleDir = Path.of("roles").resolve(role.name());
            if (!Files.exists(roleDir)) {
                // Return or throw error? Ansible usually fails if role is not found.
                // For now, we'll try to just return to avoid crashing if it's not strictly according to layout
                return;
            }
        }

        // Load defaults/main.yml (Level 2)
        variableManager.addRoleDefaults(role.name(), loadRoleVarsFile(roleDir, "defaults"));
        // Load vars/main.yml (Level 15)
        variableManager.addRoleVars(role.name(), loadRoleVarsFile(roleDir, "vars"));

        // Load tasks/main.yml
        Path tasksFile = roleDir.resolve("tasks").resolve("main.yml");
        if (!Files.exists(tasksFile)) {
            tasksFile = roleDir.resolve("tasks").resolve("main.yaml");
        }

        if (Files.exists(tasksFile)) {
            try (InputStream is = new FileInputStream(tasksFile.toFile())) {
                YamlParser parser = new YamlParser();
                List<Task> roleTasks = parser.parseTasks(is, play.tags());

                for (Task task : roleTasks) {
                    if (!isTaskToBeExecuted(task, runTags, skipTags)) continue;

                    boolean executedOnce = false;
                    for (Host host : targetHosts) {
                        if (failedHosts.contains(host.name())) continue;
                        if (task.runOnce() && executedOnce) continue;

                        Map<String, Object> vars = variableManager.getAllVariables(play, host, task, null, role.vars(), null);
                        boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);

                        try {
                            Connection connection = getOrCreateConnection(host, vars);
                            executeTaskOnHost(play, host, task, inventory, variableManager, results, failedHosts, hostNotifications, playCheckMode, null, null, role.vars(), null, connection, runTags, skipTags);
                        } catch (UnreachableException e) {
                            if (task.ignoreUnreachable()) {
                                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.unreachable(e.getMessage()));
                            } else {
                                failedHosts.add(host.name());
                            }
                        }
                        executedOnce = true;
                    }
                }
            } catch (Exception e) {
                // Log or handle task loading error
            }
        }
    }

    private Map<String, Object> loadRoleVarsFile(Path roleDir, String subDir) {
        Path varsFile = roleDir.resolve(subDir).resolve("main.yml");
        if (!Files.exists(varsFile)) {
            varsFile = roleDir.resolve(subDir).resolve("main.yaml");
        }
        if (!Files.exists(varsFile)) {
            return Collections.emptyMap();
        }

        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (InputStream is = new FileInputStream(varsFile.toFile())) {
            Object raw = yaml.load(is);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
        } catch (Exception e) {
            // Ignore loading errors
        }
        return Collections.emptyMap();
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

    private void processAddHost(TaskResult result, Inventory inventory) {
        Map<String, Object> data = result.data();
        if (data == null) return;

        // In Ansible, add_host returns data under 'add_host' key
        Map<String, Object> addHostData = (Map<String, Object>) data.get("add_host");
        if (addHostData == null) {
            addHostData = data;
        }

        String name = (String) addHostData.get("name");
        if (name == null) {
            name = (String) addHostData.get("host_name");
        }
        if (name == null) return;

        Object groupsObj = addHostData.get("groups");
        List<String> groups = new ArrayList<>();
        if (groupsObj instanceof List<?> list) {
            for (Object o : list) groups.add(o.toString());
        } else if (groupsObj instanceof String s) {
            groups.add(s);
        }

        if (groups.isEmpty()) {
            inventory.addHostToGroup(name, "all");
        } else {
            for (String groupName : groups) {
                inventory.addHostToGroup(name, groupName);
            }
        }

        // Add host-specific variables from add_host
        Optional<Host> hostOpt = inventory.getHost(name);
        if (hostOpt.isPresent()) {
            Host host = hostOpt.get();
            Map<String, Object> varsToAdd = new HashMap<>();
            Object hostVarsObj = addHostData.get("host_vars");
            if (hostVarsObj instanceof Map) {
                varsToAdd.putAll((Map<String, Object>) hostVarsObj);
            }

            for (Map.Entry<String, Object> entry : addHostData.entrySet()) {
                String key = entry.getKey();
                if (!List.of("name", "host_name", "groups", "host_vars", "changed", "failed").contains(key)) {
                    varsToAdd.put(key, entry.getValue());
                }
            }
            host.variables().putAll(varsToAdd);
        }
    }

    private void processGroupBy(TaskResult result, Inventory inventory, String currentHostName) {
        Map<String, Object> data = result.data();
        if (data == null) return;

        String groupName = (String) data.get("add_group");
        if (groupName == null) {
            groupName = (String) data.get("key");
        }
        if (groupName == null) return;

        inventory.addHostToGroup(currentHostName, groupName);
    }
}
