package org.example.ansible.inventory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses inventory files in INI format.
 */
public class IniInventoryParser implements InventoryParser {

    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[(.+)]$");

    @Override
    public Inventory parse(final InputStream inputStream) {
        final Map<String, List<String>> groupHostNames = new HashMap<>();
        final Map<String, List<String>> groupChildrenNames = new HashMap<>();
        final Map<String, Map<String, Object>> groupVars = new HashMap<>();
        final Map<String, Map<String, Object>> hostVars = new HashMap<>();
        final List<String> ungroupedHostNames = new ArrayList<>();

        String currentGroup = null;
        boolean isChildrenSection = false;
        boolean isVarsSection = false;

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }

                final Matcher matcher = SECTION_PATTERN.matcher(line);
                if (matcher.matches()) {
                    final String section = matcher.group(1);
                    if (section.endsWith(":children")) {
                        currentGroup = section.substring(0, section.length() - 9);
                        isChildrenSection = true;
                        isVarsSection = false;
                    } else if (section.endsWith(":vars")) {
                        currentGroup = section.substring(0, section.length() - 5);
                        isChildrenSection = false;
                        isVarsSection = true;
                    } else {
                        currentGroup = section;
                        isChildrenSection = false;
                        isVarsSection = false;
                    }
                    continue;
                }

                if (isVarsSection) {
                    parseKeyValuePair(line, groupVars.computeIfAbsent(currentGroup, k -> new HashMap<>()));
                } else if (isChildrenSection) {
                    groupChildrenNames.computeIfAbsent(currentGroup, k -> new ArrayList<>()).add(line);
                } else {
                    final String hostName = parseHostLine(line, hostVars);
                    if (currentGroup == null) {
                        ungroupedHostNames.add(hostName);
                    } else {
                        groupHostNames.computeIfAbsent(currentGroup, k -> new ArrayList<>()).add(hostName);
                    }
                }
            }
        } catch (final Exception e) {
            throw new RuntimeException("Failed to parse INI inventory", e);
        }

        return buildInventory(ungroupedHostNames, groupHostNames, groupChildrenNames, groupVars, hostVars);
    }

    private String parseHostLine(final String line, final Map<String, Map<String, Object>> hostVars) {
        final String[] parts = line.split("\\s+", 2);
        final String hostName = parts[0];
        if (parts.length > 1) {
            final Map<String, Object> vars = hostVars.computeIfAbsent(hostName, k -> new HashMap<>());
            final String remaining = parts[1];
            // Simple space-separated key=value parsing
            final String[] kvPairs = remaining.split("\\s+");
            for (final String kv : kvPairs) {
                parseKeyValuePair(kv, vars);
            }
        }
        return hostName;
    }

    private void parseKeyValuePair(final String kv, final Map<String, Object> targetMap) {
        final String[] parts = kv.split("=", 2);
        if (parts.length == 2) {
            targetMap.put(parts[0].trim(), parts[1].trim());
        }
    }

    private Inventory buildInventory(
            final List<String> ungroupedHostNames,
            final Map<String, List<String>> groupHostNames,
            final Map<String, List<String>> groupChildrenNames,
            final Map<String, Map<String, Object>> groupVars,
            final Map<String, Map<String, Object>> hostVars
    ) {
        final Map<String, Host> hostCache = new HashMap<>();
        final Map<String, Group> groupCache = new HashMap<>();

        // 1. Resolve all group names mentioned (explicitly or implicitly)
        final java.util.Set<String> allGroupNames = new java.util.HashSet<>();
        allGroupNames.addAll(groupHostNames.keySet());
        allGroupNames.addAll(groupChildrenNames.keySet());
        allGroupNames.addAll(groupVars.keySet());
        groupChildrenNames.values().forEach(allGroupNames::addAll);

        // 2. Build groups recursively to handle hierarchy correctly with immutable Records
        for (final String name : allGroupNames) {
            if (!name.equals("all")) {
                buildGroupRecursive(name, allGroupNames, groupHostNames, groupChildrenNames, groupVars, hostVars, hostCache, groupCache, new java.util.HashSet<>());
            }
        }

        // 3. Handle 'all' group
        final List<String> allRootHostNames = new ArrayList<>(ungroupedHostNames);
        allRootHostNames.addAll(groupHostNames.getOrDefault("all", List.of()));
        final List<Host> rootHosts = allRootHostNames.stream()
                .distinct()
                .map(name -> hostCache.computeIfAbsent(name, n -> new Host(n, hostVars.getOrDefault(n, Map.of()))))
                .toList();

        final List<Group> topLevelGroups = groupCache.values().stream()
                .filter(g -> !isChildOfAnyOtherThanAll(g.name(), groupChildrenNames))
                .toList();

        final Map<String, Object> allVars = groupVars.getOrDefault("all", Map.of());
        final Group allGroup = new Group("all", rootHosts, topLevelGroups, allVars);

        return new Inventory(allGroup);
    }

    private boolean isChildOfAnyOtherThanAll(final String groupName, final Map<String, List<String>> groupChildrenNames) {
        for (Map.Entry<String, List<String>> entry : groupChildrenNames.entrySet()) {
            if (entry.getKey().equals("all")) continue;
            if (entry.getValue().contains(groupName)) return true;
        }
        return false;
    }

    private Group buildGroupRecursive(
            final String name,
            final java.util.Set<String> allGroupNames,
            final Map<String, List<String>> groupHostNames,
            final Map<String, List<String>> groupChildrenNames,
            final Map<String, Map<String, Object>> groupVars,
            final Map<String, Map<String, Object>> hostVars,
            final Map<String, Host> hostCache,
            final Map<String, Group> groupCache,
            final java.util.Set<String> visiting
    ) {
        if (groupCache.containsKey(name)) {
            return groupCache.get(name);
        }
        if (visiting.contains(name)) {
            throw new RuntimeException("Circular dependency detected in inventory groups: " + name);
        }
        visiting.add(name);

        final List<String> hostNames = groupHostNames.getOrDefault(name, List.of());
        final List<Host> hosts = hostNames.stream()
                .map(n -> hostCache.computeIfAbsent(n, k -> new Host(k, hostVars.getOrDefault(k, Map.of()))))
                .toList();

        final List<String> childrenNames = groupChildrenNames.getOrDefault(name, List.of());
        final List<Group> children = new ArrayList<>();
        for (final String childName : childrenNames) {
            if (childName.equals("all")) continue; // Avoid self-reference if someone puts 'all' as child
            children.add(buildGroupRecursive(childName, allGroupNames, groupHostNames, groupChildrenNames, groupVars, hostVars, hostCache, groupCache, visiting));
        }

        final Map<String, Object> vars = groupVars.getOrDefault(name, Map.of());
        final Group group = new Group(name, hosts, List.copyOf(children), Map.copyOf(vars));
        groupCache.put(name, group);
        visiting.remove(name);
        return group;
    }

}
