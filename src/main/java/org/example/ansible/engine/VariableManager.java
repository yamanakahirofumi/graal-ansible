package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.inventory.Group;
import org.example.ansible.util.YamlUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages variable resolution and priority on the Control Node (管理ノード).
 */
public class VariableManager {
    /**
     * Variable merging behavior.
     */
    public enum HashBehaviour {
        REPLACE,
        MERGE;

        /**
         * Resolves the behavior from the ANSIBLE_HASH_BEHAVIOUR environment variable.
         * @return The resolved HashBehaviour.
         */
        public static HashBehaviour fromEnvironment() {
            String val = System.getenv("ANSIBLE_HASH_BEHAVIOUR");
            if ("merge".equalsIgnoreCase(val)) {
                return MERGE;
            }
            return REPLACE;
        }
    }

    /**
     * Special object used to represent the 'omit' variable.
     */
    public static final Object OMIT = new Object() {
        @Override
        public String toString() {
            return "__ansible_omit__";
        }
    };

    private static final Map<String, Object> ANSIBLE_VERSION = Map.of(
            "full", "2.21.0",
            "major", 2,
            "minor", 21,
            "revision", 0,
            "string", "2.21.0"
    );

    private final Inventory inventory;
    private final Map<String, Object> cliVars;
    private final Map<String, Object> extraVars;
    private final Map<String, Map<String, Object>> registeredVars = new HashMap<>();
    private final Map<String, Map<String, Object>> includedVars = new HashMap<>();
    private final Map<String, Map<String, Object>> setFactVars = new HashMap<>();
    private final Map<String, Map<String, Object>> hostFacts = new HashMap<>();
    private final Map<String, Map<String, Object>> roleDefaults = new HashMap<>();
    private final Map<String, Map<String, Object>> roleVars = new HashMap<>();
    private final Map<String, Object> promptVars = new HashMap<>();
    private final Map<Path, Map<String, Object>> varsFileCache = new HashMap<>();
    private final Map<String, Map<String, Object>> directoryVarsCache = new HashMap<>();
    private final Path baseDir;
    private final Path inventoryDir;
    private final HashBehaviour hashBehaviour;
    private final Yaml yaml = YamlUtil.createYaml();

    private List<String> playHostNames = new ArrayList<>();
    private List<String> playBatchHostNames = new ArrayList<>();
    private Set<String> failedHostNames = new HashSet<>();

    public VariableManager(Inventory inventory, Map<String, Object> extraVars) {
        this(inventory, Map.of(), extraVars, null, null);
    }

    public VariableManager(Inventory inventory, Map<String, Object> extraVars, Path baseDir) {
        this(inventory, Map.of(), extraVars, baseDir, null);
    }

    public VariableManager(Inventory inventory, Map<String, Object> extraVars, Path baseDir, Path inventoryDir) {
        this(inventory, Map.of(), extraVars, baseDir, inventoryDir);
    }

    public VariableManager(Inventory inventory, Map<String, Object> cliVars, Map<String, Object> extraVars, Path baseDir, Path inventoryDir) {
        this(inventory, cliVars, extraVars, baseDir, inventoryDir, HashBehaviour.fromEnvironment());
    }

    public VariableManager(Inventory inventory, Map<String, Object> cliVars, Map<String, Object> extraVars, Path baseDir, Path inventoryDir, HashBehaviour hashBehaviour) {
        this.inventory = inventory;
        this.cliVars = cliVars != null ? new HashMap<>(cliVars) : new HashMap<>();
        this.extraVars = extraVars != null ? new HashMap<>(extraVars) : new HashMap<>();
        this.baseDir = baseDir;
        this.inventoryDir = inventoryDir;
        this.hashBehaviour = hashBehaviour != null ? hashBehaviour : HashBehaviour.REPLACE;
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
     * Registers variables from include_vars for a specific host (Level 18).
     *
     * @param hostName The host name.
     * @param vars     The variables to register.
     */
    public void addIncludedVars(String hostName, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return;
        includedVars.computeIfAbsent(hostName, k -> new HashMap<>()).putAll(vars);
    }

    /**
     * Registers variables from set_fact for a specific host (Level 19).
     *
     * @param hostName The host name.
     * @param vars     The variables to register.
     */
    public void addSetFactVars(String hostName, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return;
        setFactVars.computeIfAbsent(hostName, k -> new HashMap<>()).putAll(vars);
    }

    /**
     * Registers role defaults (Level 2).
     *
     * @param roleName The role name.
     * @param vars     The variables to register.
     */
    public void addRoleDefaults(String roleName, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return;
        roleDefaults.computeIfAbsent(roleName, k -> new HashMap<>()).putAll(vars);
    }

    /**
     * Registers role variables (Level 15).
     *
     * @param roleName The role name.
     * @param vars     The variables to register.
     */
    public void addRoleVars(String roleName, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return;
        roleVars.computeIfAbsent(roleName, k -> new HashMap<>()).putAll(vars);
    }

    /**
     * Registers prompt variables (Level 13).
     *
     * @param vars The variables to register.
     */
    public void addPromptVars(Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return;
        promptVars.putAll(vars);
    }

    /**
     * Sets the current play context for magic variables.
     * @param playHostNames List of all hosts in the current play.
     * @param failedHostNames Set of hosts that have failed in the current play.
     */
    public void setPlayContext(List<String> playHostNames, Set<String> failedHostNames) {
        this.playHostNames = playHostNames != null ? new ArrayList<>(playHostNames) : new ArrayList<>();
        this.playBatchHostNames = new ArrayList<>(this.playHostNames);
        this.failedHostNames = failedHostNames != null ? failedHostNames : new HashSet<>();
    }

    /**
     * Sets the current play batch context for the 'ansible_play_batch' magic variable.
     * @param batchHostNames List of host names in the current batch.
     */
    public void setBatchContext(List<String> batchHostNames) {
        this.playBatchHostNames = batchHostNames != null ? new ArrayList<>(batchHostNames) : new ArrayList<>();
    }

    public void clearPromptVars() {
        promptVars.clear();
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
        Map<String, Object> vars = new HashMap<>(getAllVariables(play, new Host(hostName), null, null, null, null));
        vars.put("inventory_hostname", hostName);
        return vars;
    }

    /**
     * Resolves all variables for a given context (play, host, task).
     * Follows the priority defined in Variables-Templating.md.
     *
     * @param play          The current play.
     * @param host          The current host.
     * @param task          The current task.
     * @param blockVars     Accumulated block variables.
     * @param activeRoles   Active roles (for Level 2, 15, and 20).
     * @param includeParams Include parameters (Level 21).
     * @return A merged map of all variables.
     */
    public Map<String, Object> getAllVariables(Play play, Host host, Task task, Map<String, Object> blockVars) {
        return getAllVariables(play, host, task, blockVars, null, null, true, true);
    }

    public Map<String, Object> getAllVariables(Play play, Host host, Task task, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams) {
        return getAllVariables(play, host, task, blockVars, activeRoles, includeParams, true, true);
    }

    public Map<String, Object> getAllVariables(Play play, Host host, Task task, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, boolean includePromptVars) {
        return getAllVariables(play, host, task, blockVars, activeRoles, includeParams, includePromptVars, true);
    }

    public Map<String, Object> getAllVariables(Play play, Host host, Task task, Map<String, Object> blockVars, List<Role> activeRoles, Map<String, Object> includeParams, boolean includePromptVars, boolean includeHostVars) {
        Map<String, Object> variables = new HashMap<>();
        String hostName = host != null ? host.name() : null;

        // Level 1: CLI variables
        mergeVariables(variables, cliVars);

        // Inject 'omit' variable
        variables.put("omit", OMIT);

        List<Role> allRoles = new ArrayList<>();
        if (play != null) {
            allRoles.addAll(play.roles());
        }
        if (activeRoles != null) {
            allRoles.addAll(activeRoles);
        }

        // Level 2: Role defaults
        for (Role role : allRoles) {
            Map<String, Object> defaults = roleDefaults.get(role.name());
            if (defaults != null) {
                mergeVariables(variables, defaults);
            }
        }

        // Magic Variables
        variables.put("ansible_version", ANSIBLE_VERSION);
        if (hostName != null) {
            variables.put("inventory_hostname", hostName);
            if (baseDir != null) {
                variables.put("playbook_dir", baseDir.toAbsolutePath().toString());
            }
            if (inventoryDir != null) {
                variables.put("inventory_dir", inventoryDir.toAbsolutePath().toString());
            }
            // In a real Ansible, inventory_file would be the path to the inventory file.
            // Here we use the inventoryDir if available.
            if (inventoryDir != null) {
                variables.put("inventory_file", inventoryDir.toAbsolutePath().toString());
            }

            if (inventory != null) {
                variables.put("groups", inventory.getGroupsMap());
                variables.put("group_names", getHostGroups(hostName));
                if (includeHostVars) {
                    variables.put("hostvars", new HostVarsMap(play));
                }
            }

            // Play context magic variables
            variables.put("ansible_play_hosts_all", Collections.unmodifiableList(playHostNames));
            List<String> activePlayHosts = playHostNames.stream()
                    .filter(name -> !failedHostNames.contains(name))
                    .collect(Collectors.toList());
            variables.put("ansible_play_hosts", Collections.unmodifiableList(activePlayHosts));

            List<String> activeBatchHosts = playBatchHostNames.stream()
                    .filter(name -> !failedHostNames.contains(name))
                    .collect(Collectors.toList());
            variables.put("ansible_play_batch", Collections.unmodifiableList(activeBatchHosts));

            // Propagate CLI settings as magic variables
            if (cliVars.containsKey("ansible_check_mode")) {
                variables.put("ansible_check_mode", cliVars.get("ansible_check_mode"));
            }
            if (cliVars.containsKey("ansible_diff_mode")) {
                variables.put("ansible_diff_mode", cliVars.get("ansible_diff_mode"));
            }
            if (cliVars.containsKey("ansible_verbosity")) {
                variables.put("ansible_verbosity", cliVars.get("ansible_verbosity"));
            }
            if (cliVars.containsKey("ansible_run_tags")) {
                variables.put("ansible_run_tags", cliVars.get("ansible_run_tags"));
            }
            if (cliVars.containsKey("ansible_skip_tags")) {
                variables.put("ansible_skip_tags", cliVars.get("ansible_skip_tags"));
            }
        }

        // 3-10. Inventory and Directory Variables
        if (hostName != null) {
            // Level 3: Inventory group variables
            if (inventory != null) {
                mergeVariables(variables, inventory.getGroupVariablesForHost(hostName));
            }

            // Level 4: Inventory group_vars/all
            mergeVariables(variables, loadDirectoryVars(inventoryDir, "group_vars", "all"));

            // Level 5: Playbook group_vars/all
            mergeVariables(variables, loadDirectoryVars(baseDir, "group_vars", "all"));

            // Get groups for Levels 6 and 7
            List<String> groups = getHostGroups(hostName);

            // Level 6: Inventory group_vars/*
            for (String group : groups) {
                if (!"all".equals(group)) {
                    mergeVariables(variables, loadDirectoryVars(inventoryDir, "group_vars", group));
                }
            }

            // Level 7: Playbook group_vars/*
            for (String group : groups) {
                if (!"all".equals(group)) {
                    mergeVariables(variables, loadDirectoryVars(baseDir, "group_vars", group));
                }
            }

            // Level 8: Inventory host variables
            if (inventory != null) {
                mergeVariables(variables, inventory.getHostVariables(hostName));
            }

            // Level 9: Inventory host_vars/*
            mergeVariables(variables, loadDirectoryVars(inventoryDir, "host_vars", hostName));

            // Level 10: Playbook host_vars/*
            mergeVariables(variables, loadDirectoryVars(baseDir, "host_vars", hostName));
        }

        // Level 11: Host facts
        if (hostName != null && hostFacts.containsKey(hostName)) {
            mergeVariables(variables, hostFacts.get(hostName));
        }

        // Level 12: Play variables
        if (play != null) {
            mergeVariables(variables, play.vars());
        }

        // Level 13: Play vars_prompt
        if (includePromptVars) {
            mergeVariables(variables, promptVars);
        }

        // Level 14: Play vars_files
        if (play != null && !play.varsFiles().isEmpty()) {
            for (String varsFile : play.varsFiles()) {
                mergeVariables(variables, loadVarsFile(varsFile));
            }
        }

        // Level 15: Role variables
        for (Role role : allRoles) {
            Map<String, Object> vars = roleVars.get(role.name());
            if (vars != null) {
                mergeVariables(variables, vars);
            }
        }

        // Level 16: Block variables
        if (blockVars != null) {
            mergeVariables(variables, blockVars);
        }

        // Level 17: Task variables
        if (task != null) {
            mergeVariables(variables, task.vars());
        }

        // Level 18: included_vars
        if (hostName != null && includedVars.containsKey(hostName)) {
            mergeVariables(variables, includedVars.get(hostName));
        }

        // Level 19: Registered variables / set_fact
        if (hostName != null) {
            if (setFactVars.containsKey(hostName)) {
                mergeVariables(variables, setFactVars.get(hostName));
            }
            if (registeredVars.containsKey(hostName)) {
                mergeVariables(variables, registeredVars.get(hostName));
            }
        }

        // Level 20: Role parameters
        for (Role role : allRoles) {
            mergeVariables(variables, role.vars());
        }

        // Level 21: Include parameters
        if (includeParams != null) {
            mergeVariables(variables, includeParams);
        }

        // Level 22: Extra variables
        mergeVariables(variables, extraVars);

        return Collections.unmodifiableMap(variables);
    }

    private void mergeVariables(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) return;
        if (hashBehaviour == HashBehaviour.MERGE) {
            mergeRecursive(target, source);
        } else {
            target.putAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeRecursive(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map && target.get(key) instanceof Map) {
                Map<String, Object> targetSubMap = new HashMap<>((Map<String, Object>) target.get(key));
                mergeRecursive(targetSubMap, (Map<String, Object>) value);
                target.put(key, targetSubMap);
            } else {
                target.put(key, value);
            }
        }
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
     * Internal method to get registered, included, and fact variables.
     */
    public Map<String, Object> getHostRuntimeVariables(String hostName) {
        Map<String, Object> runtimeVars = new HashMap<>();
        if (hostFacts.containsKey(hostName)) {
            runtimeVars.putAll(hostFacts.get(hostName));
        }
        if (includedVars.containsKey(hostName)) {
            runtimeVars.putAll(includedVars.get(hostName));
        }
        if (setFactVars.containsKey(hostName)) {
            runtimeVars.putAll(setFactVars.get(hostName));
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
        if (varsFileCache.containsKey(path)) {
            return varsFileCache.get(path);
        }
        try (InputStream is = new FileInputStream(path.toFile())) {
            Object raw = yaml.load(is);
            if (raw instanceof Map<?, ?> map) {
                Map<String, Object> result = (Map<String, Object>) map;
                varsFileCache.put(path, result);
                return result;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load vars file: " + path, e);
        }
        return Map.of();
    }

    private Map<String, Object> loadDirectoryVars(Path dir, String subDir, String name) {
        if (dir == null) return Map.of();
        String cacheKey = dir.toString() + ":" + subDir + ":" + name;
        if (directoryVarsCache.containsKey(cacheKey)) {
            return directoryVarsCache.get(cacheKey);
        }

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
        directoryVarsCache.put(cacheKey, vars);
        return vars;
    }

    /**
     * Lazy map for hostvars magic variable.
     */
    private class HostVarsMap extends AbstractMap<String, Object> {
        private final Play play;
        private final Map<String, Map<String, Object>> cache = new HashMap<>();

        public HostVarsMap(Play play) {
            this.play = play;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            if (inventory == null) return Collections.emptySet();
            return inventory.getAllHostNames().stream()
                    .map(name -> new SimpleImmutableEntry<>(name, get(name)))
                    .collect(Collectors.toSet());
        }

        @Override
        public Object get(Object key) {
            if (!(key instanceof String hostName)) return null;
            if (cache.containsKey(hostName)) return cache.get(hostName);

            if (inventory != null && inventory.getHost(hostName).isPresent()) {
                // To avoid infinite recursion, we don't include prompt vars or other hostvars when resolving via hostvars
                Map<String, Object> vars = getAllVariables(play, inventory.getHost(hostName).get(), null, null, null, null, false, false);
                cache.put(hostName, vars);
                return vars;
            }
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            return inventory != null && key instanceof String hostName && inventory.getHost(hostName).isPresent();
        }
    }
}
