package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Host;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Free strategy: executes tasks as fast as possible for each host independently.
 */
public class FreeStrategy implements Strategy {
    private static final Logger LOGGER = Logger.getLogger(FreeStrategy.class.getName());

    @Override
    public void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags) {
        int forks = tqm.getForks();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(targetHosts.size(), forks));
        Set<String> failedHosts = tqm.getFailedHosts();
        Map<String, Set<String>> hostNotifications = tqm.getHostNotifications();
        Set<Task> runOnceExecuted = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (Host host : targetHosts) {
            executor.submit(() -> {
                try {
                    // Roles
                    for (Role role : play.roles()) {
                        if (tqm.isPlayFatalError()) return;
                        if (failedHosts.contains(host.name())) return;
                        tqm.executeRole(play, role, List.of(host), variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
                    }

                    // Tasks
                    for (Task task : play.tasks()) {
                        if (tqm.isPlayFatalError()) return;
                        if (failedHosts.contains(host.name())) return;

                        if (task.runOnce() && !runOnceExecuted.add(task)) {
                            continue;
                        }

                        if (!tqm.isTaskToBeExecuted(task, runTags, skipTags)) {
                            results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                            continue;
                        }

                        tqm.getCallbacks().forEach(c -> c.v2_playbook_on_task_start(task, task.when() != null));

                        Map<String, Object> vars = variableManager.getAllVariables(play, host, task, null, (List<Role>) null, null);
                        boolean playCheckMode = tqm.getVariableResolver().resolveCheckMode(play.checkMode(), vars, globalCheckMode);

                        try {
                            Connection connection = tqm.getOrCreateConnection(host, vars);
                            tqm.executeTaskOnHost(play, host, task, variableManager, results, failedHosts, hostNotifications, playCheckMode, new ArrayList<>(), null, null, null, connection, runTags, skipTags);
                        } catch (UnreachableException e) {
                            if (task.ignoreUnreachable()) {
                                TaskResult unreachableResult = TaskResult.unreachable(e.getMessage());
                                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(unreachableResult);
                            } else {
                                failedHosts.add(host.name());
                                tqm.checkAnyErrorsFatal(play, host, task, null, null, null, variableManager);
                            }
                        }
                    }

                    // Handlers
                    if (!tqm.isPlayFatalError() && !failedHosts.contains(host.name())) {
                        Map<String, Object> vars = variableManager.getAllVariables(play, host, null, null, (List<Role>) null, null);
                        boolean playCheckMode = tqm.getVariableResolver().resolveCheckMode(play.checkMode(), vars, globalCheckMode);
                        try {
                            Connection connection = tqm.getOrCreateConnection(host, vars);
                            tqm.flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, playCheckMode, connection, runTags, skipTags);
                        } catch (UnreachableException e) {
                            failedHosts.add(host.name());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Error executing tasks for host: " + host.name(), e);
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Free strategy execution interrupted", e);
        }
    }

    @Override
    public String getName() {
        return "free";
    }
}
