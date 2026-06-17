package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linear strategy: executes tasks one by one for all hosts.
 */
public class LinearStrategy implements Strategy {
    @Override
    public void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags) {
        Set<String> failedHosts = tqm.getFailedHosts();
        Map<String, Set<String>> hostNotifications = tqm.getHostNotifications();

        for (Role role : play.roles()) {
            if (tqm.isPlayFatalError()) break;
            tqm.executeRole(play, role, targetHosts, variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
        }

        for (Task task : play.tasks()) {
            if (tqm.isPlayFatalError()) break;
            if (!tqm.isTaskToBeExecuted(task, runTags, skipTags)) {
                for (Host host : targetHosts) {
                    results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                }
                continue;
            }

            tqm.getCallbacks().forEach(c -> c.v2_playbook_on_task_start(task, task.when() != null));

            boolean executedOnce = false;
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                if (task.runOnce() && executedOnce) {
                    continue;
                }

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
                executedOnce = true;
            }
        }

        // Execute handlers at the end of the play
        if (!tqm.isPlayFatalError()) {
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }
                Map<String, Object> vars = variableManager.getAllVariables(play, host, null, null, (List<Role>) null, null);
                boolean playCheckMode = tqm.getVariableResolver().resolveCheckMode(play.checkMode(), vars, globalCheckMode);
                try {
                    Connection connection = tqm.getOrCreateConnection(host, vars);
                    tqm.flushHandlersForHost(play, host, variableManager, results, failedHosts, hostNotifications, playCheckMode, connection, runTags, skipTags);
                } catch (UnreachableException e) {
                    failedHosts.add(host.name());
                }
            }
        }
    }

    @Override
    public String getName() {
        return "linear";
    }
}
