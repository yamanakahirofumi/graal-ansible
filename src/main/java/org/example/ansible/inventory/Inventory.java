package org.example.ansible.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents the entire inventory.
 *
 * @param all The root group containing all hosts and groups.
 */
public record Inventory(Group all) {

    /**
     * Resolves all variables for a given host by name.
     * Follows the priority: all group < parent groups < child groups < host variables.
     *
     * @param hostName The name of the host.
     * @return A map of resolved variables.
     */
    public Map<String, Object> getVariablesForHost(String hostName) {
        Map<String, Object> resolvedVars = new HashMap<>();

        // 1. Start with 'all' group variables
        resolvedVars.putAll(all.variables());

        // 2. Find all paths to the host and collect group variables
        List<List<Group>> paths = new ArrayList<>();
        findPathsToHost(all, hostName, new ArrayList<>(), paths);

        // Merge group variables along the paths. 
        // In case of multiple paths (host in multiple groups), we merge them all.
        // Child groups override parent groups within a path.
        for (List<Group> path : paths) {
            for (Group group : path) {
                resolvedVars.putAll(group.variables());
            }
        }

        // 3. Finally, add host-specific variables
        findHost(hostName).ifPresent(host -> resolvedVars.putAll(host.variables()));

        return Map.copyOf(resolvedVars);
    }

    private void findPathsToHost(Group current, String hostName, List<Group> currentPath, List<List<Group>> paths) {
        List<Group> newPath = new ArrayList<>(currentPath);
        if (!current.name().equals("all")) {
            newPath.add(current);
        }

        boolean hostInGroup = current.hosts().stream().anyMatch(h -> h.name().equals(hostName));
        if (hostInGroup) {
            paths.add(newPath);
        }

        for (Group child : current.children()) {
            findPathsToHost(child, hostName, newPath, paths);
        }
    }

    private Optional<Host> findHost(String hostName) {
        return findHostInGroup(all, hostName);
    }

    private Optional<Host> findHostInGroup(Group group, String hostName) {
        Optional<Host> host = group.hosts().stream().filter(h -> h.name().equals(hostName)).findFirst();
        if (host.isPresent()) {
            return host;
        }
        for (Group child : group.children()) {
            host = findHostInGroup(child, hostName);
            if (host.isPresent()) {
                return host;
            }
        }
        return Optional.empty();
    }
}
