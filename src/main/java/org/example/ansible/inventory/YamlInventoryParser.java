package org.example.ansible.inventory;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

/**
 * Parses inventory files in YAML format.
 */
public class YamlInventoryParser implements InventoryParser {
    private final Yaml yaml = new Yaml();

    @Override
    public Inventory parse(InputStream inputStream) {
        Object raw = yaml.load(inputStream);
        if (raw == null) {
            return new Inventory(new Group("all"));
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Invalid YAML inventory format: expected a map at the root");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) raw;

        Map<String, Group> groupCache = new HashMap<>();
        Map<String, Map<String, Object>> hostVarsMap = new HashMap<>();
        Set<String> visiting = new HashSet<>();

        Group allGroup;
        if (rootMap.containsKey("all")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> allData = (Map<String, Object>) rootMap.get("all");
            allGroup = parseGroup("all", allData, groupCache, hostVarsMap, rootMap, visiting);
        } else {
            List<Group> children = new ArrayList<>();
            for (Map.Entry<String, Object> entry : rootMap.entrySet()) {
                String groupName = entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> groupData) {
                    @SuppressWarnings("unchecked")
                    Group group = parseGroup(groupName, (Map<String, Object>) groupData, groupCache, hostVarsMap, rootMap, visiting);
                    children.add(group);
                }
            }
            allGroup = new Group("all", Collections.emptyList(), List.copyOf(children), Collections.emptyMap());
        }

        // Second pass: properly build groups with shared Host records that have merged variables
        Map<String, Host> hostCache = new HashMap<>();
        hostVarsMap.forEach((name, vars) -> hostCache.put(name, new Host(name, Map.copyOf(vars))));

        Map<String, Group> finalGroupCache = new HashMap<>();
        Group finalAllGroup = rebuildGroup(allGroup, finalGroupCache, hostCache);

        return new Inventory(finalAllGroup);
    }

    private Group parseGroup(String name, Map<String, Object> data, Map<String, Group> groupCache,
                             Map<String, Map<String, Object>> hostVarsMap, Map<String, Object> rootMap, Set<String> visiting) {
        if (groupCache.containsKey(name)) {
            return groupCache.get(name);
        }
        if (visiting.contains(name)) {
            throw new RuntimeException("Circular dependency detected in inventory groups: " + name);
        }
        visiting.add(name);

        List<Host> hosts = new ArrayList<>();
        if (data.containsKey("hosts") && data.get("hosts") != null) {
            Object hostsObj = data.get("hosts");
            if (hostsObj instanceof Map<?, ?> hostsMap) {
                for (Map.Entry<?, ?> entry : hostsMap.entrySet()) {
                    String hostName = (String) entry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> vars = (entry.getValue() instanceof Map) ? (Map<String, Object>) entry.getValue() : Collections.emptyMap();
                    hostVarsMap.computeIfAbsent(hostName, k -> new HashMap<>()).putAll(vars);
                    hosts.add(new Host(hostName, Map.copyOf(vars))); // Temporary Host record
                }
            } else if (hostsObj instanceof List<?> hostsList) {
                for (Object item : hostsList) {
                    if (item != null) {
                        String hostName = item.toString();
                        hostVarsMap.putIfAbsent(hostName, new HashMap<>());
                        hosts.add(new Host(hostName, Collections.emptyMap())); // Temporary Host record
                    }
                }
            }
        }

        List<Group> children = new ArrayList<>();
        if (data.containsKey("children") && data.get("children") != null) {
            Object childrenObj = data.get("children");
            if (childrenObj instanceof Map<?, ?> childrenMap) {
                for (Map.Entry<?, ?> entry : childrenMap.entrySet()) {
                    String childName = (String) entry.getKey();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> childData = (entry.getValue() instanceof Map) ? (Map<String, Object>) entry.getValue() : Collections.emptyMap();
                    children.add(parseGroup(childName, childData, groupCache, hostVarsMap, rootMap, visiting));
                }
            } else if (childrenObj instanceof List<?> childrenList) {
                for (Object item : childrenList) {
                    if (item != null) {
                        String childName = item.toString();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> childData = (Map<String, Object>) rootMap.getOrDefault(childName, Collections.emptyMap());
                        children.add(parseGroup(childName, childData, groupCache, hostVarsMap, rootMap, visiting));
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> vars = data.containsKey("vars") && data.get("vars") instanceof Map ? (Map<String, Object>) data.get("vars") : Collections.emptyMap();

        Group group = new Group(name, List.copyOf(hosts), List.copyOf(children), Map.copyOf(vars));
        groupCache.put(name, group);
        visiting.remove(name);
        return group;
    }

    private Group rebuildGroup(Group group, Map<String, Group> finalGroupCache, Map<String, Host> hostCache) {
        if (finalGroupCache.containsKey(group.name())) {
            return finalGroupCache.get(group.name());
        }

        List<Host> rebuiltHosts = group.hosts().stream()
                .map(h -> hostCache.get(h.name()))
                .toList();

        List<Group> rebuiltChildren = group.children().stream()
                .map(child -> rebuildGroup(child, finalGroupCache, hostCache))
                .toList();

        Group rebuiltGroup = new Group(group.name(), rebuiltHosts, rebuiltChildren, group.variables());
        finalGroupCache.put(group.name(), rebuiltGroup);
        return rebuiltGroup;
    }
}
