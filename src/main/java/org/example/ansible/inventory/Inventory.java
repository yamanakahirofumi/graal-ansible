package org.example.ansible.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents the entire inventory.
 */
public class Inventory {
    private final Group all;

    public Inventory(Group all) {
        this.all = all;
    }

    public Group all() {
        return all;
    }

    /**
     * Resolves all variables for a given host by name.
     * Follows the priority: all group < parent groups < child groups < host variables.
     *
     * @param hostName The name of the host.
     * @return A map of resolved variables.
     */
    public Map<String, Object> getVariablesForHost(String hostName) {
        Map<String, Object> resolvedVars = new HashMap<>();
        resolvedVars.putAll(getGroupVariablesForHost(hostName));
        resolvedVars.putAll(getHostVariables(hostName));
        return Map.copyOf(resolvedVars);
    }

    /**
     * Gets all group variables for a given host, following hierarchy. (Level 3)
     *
     * @param hostName The name of the host.
     * @return A map of resolved group variables.
     */
    public Map<String, Object> getGroupVariablesForHost(String hostName) {
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
        return resolvedVars;
    }

    /**
     * Gets host-specific variables from the inventory. (Level 8)
     *
     * @param hostName The name of the host.
     * @return A map of host variables.
     */
    public Map<String, Object> getHostVariables(String hostName) {
        return findHost(hostName)
                .map(Host::variables)
                .orElse(Map.of());
    }

    /**
     * Gets a map of all groups and their host names.
     * @return A map of group name to list of host names.
     */
    public Map<String, List<String>> getGroupsMap() {
        Map<String, List<String>> groupsMap = new HashMap<>();
        collectGroups(all, groupsMap);
        return Map.copyOf(groupsMap);
    }

    private void collectGroups(Group group, Map<String, List<String>> groupsMap) {
        List<String> hostNames = group.hosts().stream().map(Host::name).toList();
        groupsMap.put(group.name(), hostNames);
        for (Group child : group.children()) {
            collectGroups(child, groupsMap);
        }
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
