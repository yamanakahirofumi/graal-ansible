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

        List<List<Host>> batches = calculateBatches(targetHosts, play.serial());

        for (List<Host> batch : batches) {
            if (tqm.isPlayFatalError()) break;

            variableManager.setBatchContext(batch.stream().map(Host::name).toList());

            // Roles for this batch
            for (Role role : play.roles()) {
                if (tqm.isPlayFatalError()) break;
                tqm.executeRole(play, role, batch, variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
            }

            // Tasks for this batch
            for (Task task : play.tasks()) {
                if (tqm.isPlayFatalError()) break;
                if (!tqm.isTaskToBeExecuted(task, runTags, skipTags)) {
                    for (Host host : batch) {
                        results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(TaskResult.skipped("Skipped due to tags"));
                    }
                    continue;
                }

                tqm.getCallbacks().forEach(c -> c.v2_playbook_on_task_start(task, task.when() != null));

                boolean executedOnce = false;
                for (Host host : batch) {
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
                tqm.checkMaxFailPercentage(play, task, batch, failedHosts, variableManager);
            }

            // Execute handlers for this batch
            if (!tqm.isPlayFatalError()) {
                for (Host host : batch) {
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

    private List<List<Host>> calculateBatches(List<Host> targetHosts, Object serial) {
        if (serial == null) {
            return List.of(new ArrayList<>(targetHosts));
        }

        List<Integer> batchSizes = new ArrayList<>();
        if (serial instanceof Integer i) {
            batchSizes.add(i);
        } else if (serial instanceof String s && s.endsWith("%")) {
            int percent = Integer.parseInt(s.substring(0, s.length() - 1));
            int size = (int) Math.max(1, Math.floor(targetHosts.size() * percent / 100.0));
            batchSizes.add(size);
        } else if (serial instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Integer i) {
                    batchSizes.add(i);
                } else if (o instanceof String s && s.endsWith("%")) {
                    int percent = Integer.parseInt(s.substring(0, s.length() - 1));
                    int size = (int) Math.max(1, Math.floor(targetHosts.size() * percent / 100.0));
                    batchSizes.add(size);
                }
            }
        } else if (serial instanceof String s) {
            try {
                batchSizes.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                return List.of(new ArrayList<>(targetHosts));
            }
        }

        if (batchSizes.isEmpty()) {
            return List.of(new ArrayList<>(targetHosts));
        }

        List<List<Host>> batches = new ArrayList<>();
        int currentHostIdx = 0;
        int sizeIdx = 0;
        while (currentHostIdx < targetHosts.size()) {
            int currentBatchSize = batchSizes.get(Math.min(sizeIdx, batchSizes.size() - 1));
            if (currentBatchSize <= 0) {
                // To avoid infinite loop if size is 0 or less
                currentBatchSize = targetHosts.size();
            }
            int endIdx = Math.min(currentHostIdx + currentBatchSize, targetHosts.size());
            batches.add(new ArrayList<>(targetHosts.subList(currentHostIdx, endIdx)));
            currentHostIdx = endIdx;
            sizeIdx++;
        }
        return batches;
    }

    @Override
    public String getName() {
        return "linear";
    }
}
