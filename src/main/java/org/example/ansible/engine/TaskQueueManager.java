package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.connection.DefaultConnectionFactory;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.util.Truthiness;

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
        List<Host> targetHosts = getTargetHosts(play.hosts(), inventory);
        Set<String> failedHosts = new HashSet<>();
        Map<String, Set<String>> hostNotifications = new HashMap<>();

        try {
            for (Task task : play.tasks()) {
                boolean executedOnce = false;
                for (Host host : targetHosts) {
                    if (failedHosts.contains(host.name())) {
                        continue;
                    }
                    if (task.runOnce() && executedOnce) {
                        continue;
                    }

                    // Initial inherited check mode from Play level
                    Map<String, Object> vars = variableManager.getAllVariables(play, host, task);
                    boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);

                    Connection connection = getOrCreateConnection(host, vars);
                    executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, playCheckMode, null, connection);
                    executedOnce = true;
                }
            }

            // Execute handlers at the end of the play
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                Map<String, Object> vars = variableManager.getAllVariables(play, host, null);
                boolean playCheckMode = variableResolver.resolveCheckMode(play.checkMode(), vars, globalCheckMode);
                Connection connection = getOrCreateConnection(host, vars);
                flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, playCheckMode, connection);
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

    private void flushHandlersForHost(Play play, Host host, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Connection connection) {
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
                                executeTaskOnHost(play, host, handler, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, null, connection);
                                anyNewNotified = true;
                                break;
                            }
                        }
                    }
                }
            }
        } while (anyNewNotified);
    }

    private void executeTaskOnHost(Play play, Host host, Task task, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection) {
        if (!task.block().isEmpty()) {
            executeBlock(play, host, task, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, inheritedEnvironment, connection);
            return;
        }

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, inheritedCheckMode, inheritedEnvironment, connection, connectionFactory);

        if (result != null) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(result);

            if (result.success() && !result.isSkipped() && "meta".equals(task.action()) && "flush_handlers".equals(task.args().get("_raw_params"))) {
                flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, inheritedCheckMode, connection);
            }

            if (task.register() != null) {
                variableManager.registerVariable(host.name(), task.register(), result.data());
            }

            if (result.changed() && !task.notifications().isEmpty()) {
                hostNotifications.computeIfAbsent(host.name(), k -> new HashSet<>()).addAll(task.notifications());
            }

            if (!result.success() && !result.isSkipped()) {
                if (!task.ignoreErrors()) {
                    failedHosts.add(host.name());
                }
            }
        }
    }

    private void executeBlock(Play play, Host host, Task blockTask, VariableManager variableManager, Map<String, List<TaskResult>> results, Set<String> failedHosts, Map<String, Set<String>> hostNotifications, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection) {
        Map<String, Object> blockVars = variableManager.getAllVariables(play, host, blockTask);
        boolean blockCheckMode = variableResolver.resolveCheckMode(blockTask.checkMode(), blockVars, inheritedCheckMode);

        if (!variableResolver.isWhenConditionMet(blockTask.when(), blockVars)) {
            results.computeIfAbsent(host.name(), k -> new ArrayList<>())
                    .add(TaskResult.skipped("Skipped due to block when condition"));
            return;
        }

        boolean blockFailed = false;
        Set<String> blockFailedHosts = new HashSet<>();
        Object effectiveBlockEnv = blockTask.environment() != null ? blockTask.environment() : inheritedEnvironment;

        for (Task task : blockTask.block()) {
            if (blockFailedHosts.contains(host.name())) {
                blockFailed = true;
                break;
            }
            executeTaskOnHost(play, host, task, variableManager, results, blockFailedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, connection);
        }

        if (blockFailedHosts.contains(host.name())) {
            blockFailed = true;
        }

        if (blockFailed) {
            for (Task task : blockTask.rescue()) {
                executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, connection);
            }
        }

        for (Task task : blockTask.always()) {
            executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, blockCheckMode, effectiveBlockEnv, connection);
        }

        if (blockFailed && blockTask.rescue().isEmpty()) {
            failedHosts.add(host.name());
        }
    }


    private List<Host> getTargetHosts(String pattern, Inventory inventory) {
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

    private List<Host> getAllHosts(Group group) {
        List<Host> hosts = new ArrayList<>(group.hosts());
        for (Group child : group.children()) {
            hosts.addAll(getAllHosts(child));
        }
        return hosts.stream().distinct().toList();
    }

    private Group findGroup(Group root, String name) {
        if (root.name().equals(name)) return root;
        for (Group child : root.children()) {
            Group found = findGroup(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
