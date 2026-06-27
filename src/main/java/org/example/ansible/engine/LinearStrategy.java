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
 * Supports serial batch execution.
 */
public class LinearStrategy implements Strategy {
    @Override
    public void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags) {
        List<Integer> batchSizes = calculateBatchSizes(play.serial(), targetHosts.size());
        int hostIndex = 0;

        for (int batchSize : batchSizes) {
            if (tqm.isPlayFatalError()) break;

            List<Host> batchHosts = targetHosts.subList(hostIndex, hostIndex + batchSize);
            hostIndex += batchSize;

            List<String> batchHostNames = batchHosts.stream().map(Host::name).toList();
            variableManager.setBatchContext(batchHostNames);

            Set<String> failedHosts = tqm.getFailedHosts();
            Map<String, Set<String>> hostNotifications = tqm.getHostNotifications();

            // Execute roles for this batch
            for (Role role : play.roles()) {
                if (tqm.isPlayFatalError()) break;
                tqm.executeRole(play, role, batchHosts, variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
            }

            // Execute tasks for this batch
            for (Task task : play.tasks()) {
                if (tqm.isPlayFatalError()) break;
                if (!tqm.isTaskToBeExecuted(task, runTags, skipTags)) {
                    for (Host host : batchHosts) {
                        results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                    }
                    continue;
                }

                tqm.getCallbacks().forEach(c -> c.v2_playbook_on_task_start(task, task.when() != null));

                boolean executedOnce = false;
                for (Host host : batchHosts) {
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

            // Execute handlers at the end of the batch
            if (!tqm.isPlayFatalError()) {
                for (Host host : batchHosts) {
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
    }

    private List<Integer> calculateBatchSizes(Object serial, int totalHosts) {
        List<Integer> sizes = new ArrayList<>();
        if (serial == null || totalHosts == 0) {
            if (totalHosts > 0) sizes.add(totalHosts);
            return sizes;
        }

        List<Object> serialList;
        if (serial instanceof List) {
            serialList = (List<Object>) serial;
        } else {
            serialList = List.of(serial);
        }

        int remaining = totalHosts;
        Object lastSerial = 1;
        for (Object s : serialList) {
            if (remaining <= 0) break;
            int size = parseSerialSize(s, totalHosts);
            int actualSize = Math.min(size, remaining);
            sizes.add(actualSize);
            remaining -= actualSize;
            lastSerial = s;
        }

        while (remaining > 0) {
            int size = parseSerialSize(lastSerial, totalHosts);
            int actualSize = Math.min(size, remaining);
            sizes.add(actualSize);
            remaining -= actualSize;
        }

        return sizes;
    }

    private int parseSerialSize(Object s, int totalHosts) {
        if (s instanceof Integer i) {
            return Math.max(i, 1);
        } else if (s instanceof String str) {
            str = str.trim();
            if (str.endsWith("%")) {
                try {
                    double percentage = Double.parseDouble(str.substring(0, str.length() - 1));
                    int size = (int) Math.floor(totalHosts * (percentage / 100.0));
                    return Math.max(size, 1);
                } catch (NumberFormatException e) {
                    return 1;
                }
            } else {
                try {
                    return Math.max(Integer.parseInt(str), 1);
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    @Override
    public String getName() {
        return "linear";
    }
}
