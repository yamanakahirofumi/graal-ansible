package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages variable resolution and priority on the Control Node (管理ノード).
 */
public class VariableManager {
    private final Inventory inventory;
    private final Map<String, Object> extraVars;
    private final Map<String, Map<String, Object>> registeredVars = new HashMap<>();
    private final Map<String, Map<String, Object>> hostFacts = new HashMap<>();
    private final Path baseDir;
    private final Path inventoryDir;
    private final Yaml yaml = new Yaml();

    public VariableManager(Inventory inventory, Map<String, Object> extraVars) {
        this(inventory, extraVars, null, null);
    }

    public VariableManager(Inventory inventory, Map<String, Object> extraVars, Path baseDir) {
        this(inventory, extraVars, baseDir, null);
    }

    public VariableManager(Inventory inventory, Map<String, Object> extraVars, Path baseDir, Path inventoryDir) {
        this.inventory = inventory;
        this.extraVars = extraVars != null ? new HashMap<>(extraVars) : new HashMap<>();
        this.baseDir = baseDir;
        this.inventoryDir = inventoryDir;
    }

    /**
     * Gets the base directory for file resolution.
     * @return The base directory.
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * Registers a variable for a specific host.
     *
     * @param hostName The host name.
     * @param name     The variable name.
     * @param value    The variable value.
     */
    public void registerVariable(String hostName, String name, Object value) {
        registeredVars.computeIfAbsent(hostName, k -> new HashMap<>()).put(name, value);
    }

    /**
     * Registers facts for a specific host.
     * Facts are merged into both the top-level variables and the 'ansible_facts' key.
     *
     * @param hostName The host name.
     * @param facts    The facts to register.
     */
    public void addFacts(String hostName, Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) return;
        Map<String, Object> factsMap = hostFacts.computeIfAbsent(hostName, k -> new HashMap<>());

        // Merge into top-level variables
        factsMap.putAll(facts);

        // Merge into 'ansible_facts' dictionary
        @SuppressWarnings("unchecked")
        Map<String, Object> ansibleFacts = (Map<String, Object>) factsMap.get("ansible_facts");
        if (ansibleFacts == null) {
            ansibleFacts = new HashMap<String, Object>();
            factsMap.put("ansible_facts", ansibleFacts);
        }
        ansibleFacts.putAll(facts);
    }

    /**
     * Resolves all variables for a given host without play or task context.
     *
     * @param hostName The host name.
     * @return A merged map of variables for the host.
     */
    public Map<String, Object> getVariablesForHost(String hostName) {
        return getVariablesForHost(hostName, null);
    }

    /**
     * Resolves all variables for a given host within a play context.
     *
     * @param hostName The host name.
     * @param play     The current play.
     * @return A merged map of variables for the host.
     */
    public Map<String, Object> getVariablesForHost(String hostName, Play play) {
        Map<String, Object> vars = new HashMap<>(getAllVariables(play, new Host(hostName), null, null));
        vars.put("inventory_hostname", hostName);
        return vars;
    }

    /**
     * Resolves all variables for a given context (play, host, task).
     * Follows the priority defined in Variables-Templating.md.
     *
     * @param play      The current play.
     * @param host      The current host.
     * @param task      The current task.
     * @param blockVars Accumulated block variables.
     * @return A merged map of all variables.
     */
    public Map<String, Object> getAllVariables(Play play, Host host, Task task, Map<String, Object> blockVars) {
        Map<String, Object> variables = new HashMap<>();
        String hostName = host != null ? host.name() : null;

        // Magic Variables
        if (hostName != null) {
            variables.put("inventory_hostname", hostName);
        }

        // 3-10. Inventory and Directory Variables
        if (hostName != null) {
            // Level 3: Inventory group variables
            if (inventory != null) {
                variables.putAll(inventory.getGroupVariablesForHost(hostName));
            }

            // Level 4: Inventory group_vars/all
            variables.putAll(loadDirectoryVars(inventoryDir, "group_vars", "all"));

            // Level 5: Playbook group_vars/all
            variables.putAll(loadDirectoryVars(baseDir, "group_vars", "all"));

            // Get groups for Levels 6 and 7
            List<String> groups = getHostGroups(hostName);

            // Level 6: Inventory group_vars/*
            for (String group : groups) {
                if (!"all".equals(group)) {
                    variables.putAll(loadDirectoryVars(inventoryDir, "group_vars", group));
                }
            }

            // Level 7: Playbook group_vars/*
            for (String group : groups) {
                if (!"all".equals(group)) {
                    variables.putAll(loadDirectoryVars(baseDir, "group_vars", group));
                }
            }

            // Level 8: Inventory host variables
            if (inventory != null) {
                variables.putAll(inventory.getHostVariables(hostName));
            }

            // Level 9: Inventory host_vars/*
            variables.putAll(loadDirectoryVars(inventoryDir, "host_vars", hostName));

            // Level 10: Playbook host_vars/*
            variables.putAll(loadDirectoryVars(baseDir, "host_vars", hostName));
        }

        // Level 11: Host facts
        if (hostName != null && hostFacts.containsKey(hostName)) {
            variables.putAll(hostFacts.get(hostName));
        }

        // Level 12: Play variables
        if (play != null) {
            variables.putAll(play.vars());
        }

        // Level 14: Play vars_files
        if (play != null && !play.varsFiles().isEmpty()) {
            for (String varsFile : play.varsFiles()) {
                variables.putAll(loadVarsFile(varsFile));
            }
        }

        // Level 16: Block variables
        if (blockVars != null) {
            variables.putAll(blockVars);
        }

        // Level 17: Task variables
        if (task != null) {
            variables.putAll(task.vars());
        }

        // Level 19: Registered variables
        if (hostName != null && registeredVars.containsKey(hostName)) {
            variables.putAll(registeredVars.get(hostName));
        }

        // Level 22: Extra variables
        variables.putAll(extraVars);

        return Collections.unmodifiableMap(variables);
    }

    private List<String> getHostGroups(String hostName) {
        if (inventory == null) return Collections.emptyList();
        List<String> groups = new ArrayList<>();
        findGroupsForHost(inventory.all(), hostName, groups);
        return groups;
    }

    private void findGroupsForHost(Group group, String hostName, List<String> result) {
        boolean hasHost = group.hosts().stream().anyMatch(h -> h.name().equals(hostName));
        if (hasHost) {
            if (!result.contains(group.name())) {
                result.add(group.name());
            }
        }
        for (Group child : group.children()) {
            findGroupsForHost(child, hostName, result);
        }
    }

    /**
     * Internal method to get registered and fact variables.
     */
    public Map<String, Object> getHostRuntimeVariables(String hostName) {
        Map<String, Object> runtimeVars = new HashMap<>();
        if (hostFacts.containsKey(hostName)) {
            runtimeVars.putAll(hostFacts.get(hostName));
        }
        if (registeredVars.containsKey(hostName)) {
            runtimeVars.putAll(registeredVars.get(hostName));
        }
        return runtimeVars;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadVarsFile(String varsFile) {
        Path filePath = baseDir != null ? baseDir.resolve(varsFile) : Path.of(varsFile);
        return loadVarsFileFromPath(filePath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadVarsFileFromPath(Path path) {
        if (!Files.exists(path)) return Map.of();
        try (InputStream is = new FileInputStream(path.toFile())) {
            Object raw = yaml.load(is);
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load vars file: " + path, e);
        }
        return Map.of();
    }

    private Map<String, Object> loadDirectoryVars(Path dir, String subDir, String name) {
        if (dir == null) return Map.of();
        Path varsDir = dir.resolve(subDir);
        if (!Files.exists(varsDir)) return Map.of();

        Map<String, Object> vars = new HashMap<>();

        // 1. Load <name>.yml, <name>.yaml, or <name>.json
        vars.putAll(loadVarsFileFromPath(varsDir.resolve(name + ".yml")));
        vars.putAll(loadVarsFileFromPath(varsDir.resolve(name + ".yaml")));
        vars.putAll(loadVarsFileFromPath(varsDir.resolve(name + ".json")));

        // 2. Load all files in <name>/ directory
        Path nameDir = varsDir.resolve(name);
        if (Files.isDirectory(nameDir)) {
            try (var stream = Files.list(nameDir)) {
                stream.filter(p -> !Files.isDirectory(p))
                        .filter(p -> {
                            String s = p.toString();
                            return s.endsWith(".yml") || s.endsWith(".yaml") || s.endsWith(".json");
                        })
                        .sorted()
                        .forEach(p -> vars.putAll(loadVarsFileFromPath(p)));
            } catch (IOException e) {
                // Ignore directory listing errors
            }
        }
        return vars;
    }
}
