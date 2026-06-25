package org.example.ansible.engine;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.UnreachableException;
import org.example.ansible.inventory.Host;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

            // Set batch context for magic variables
            variableManager.setBatchContext(batch.stream().map(Host::name).collect(Collectors.toList()));

            for (Role role : play.roles()) {
                if (tqm.isPlayFatalError()) break;
                tqm.executeRole(play, role, batch, variableManager, results, failedHosts, hostNotifications, globalCheckMode, runTags, skipTags);
            }

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
            }

            // Execute handlers at the end of the batch
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

    private List<List<Host>> calculateBatches(List<Host> hosts, Object serial) {
        if (hosts.isEmpty()) return List.of();
        if (serial == null) return List.of(new ArrayList<>(hosts));

        int batchSize;
        try {
            if (serial instanceof Integer i) {
                batchSize = i;
            } else if (serial instanceof String s) {
                s = s.trim();
                if (s.endsWith("%")) {
                    double percent = Double.parseDouble(s.substring(0, s.length() - 1)) / 100.0;
                    batchSize = (int) Math.max(1, Math.floor(hosts.size() * percent));
                } else {
                    batchSize = Integer.parseInt(s);
                }
            } else if (serial instanceof Number n) {
                batchSize = n.intValue();
            } else {
                return List.of(new ArrayList<>(hosts));
            }
        } catch (NumberFormatException e) {
            return List.of(new ArrayList<>(hosts));
        }

        if (batchSize <= 0) return List.of(new ArrayList<>(hosts));
        if (batchSize >= hosts.size()) return List.of(new ArrayList<>(hosts));

        List<List<Host>> batches = new ArrayList<>();
        for (int i = 0; i < hosts.size(); i += batchSize) {
            batches.add(new ArrayList<>(hosts.subList(i, Math.min(i + batchSize, hosts.size()))));
        }
        return batches;
    }

    @Override
    public String getName() {
        return "linear";
    }
}
