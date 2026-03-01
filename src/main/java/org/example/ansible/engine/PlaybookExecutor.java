package org.example.ansible.engine;

import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, List<TaskResult>> results = new HashMap<>();

        for (Play play : playbook.plays()) {
            executePlay(play, inventory, results);
        }

        return results;
    }

    private void executePlay(Play play, Inventory inventory, Map<String, List<TaskResult>> results) {
        List<Host> targetHosts = getTargetHosts(play.hosts(), inventory);
        java.util.Set<String> failedHosts = new java.util.HashSet<>();

        for (Task task : play.tasks()) {
            for (Host host : targetHosts) {
                if (failedHosts.contains(host.name())) {
                    continue;
                }

                Map<String, Object> hostVars = inventory.getVariablesForHost(host.name());

                // Variable resolution for task arguments
                Map<String, Object> resolvedArgs = variableResolver.resolve(task.args(), hostVars);
                Task resolvedTask = new Task(task.name(), task.action(), resolvedArgs);

                TaskResult result = taskExecutor.execute(resolvedTask);
                results.computeIfAbsent(host.name(), k -> new ArrayList<>()).add(result);

                if (!result.success()) {
                    failedHosts.add(host.name());
                }
            }
        }
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
}
