package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages variable resolution and priority on the Control Node (管理ノード).
 */
public class VariableManager {
    private final Inventory inventory;
    private final Map<String, Object> extraVars;
    private final Map<String, Map<String, Object>> hostVars = new HashMap<>();
    private final Path baseDir;
    private final Yaml yaml = new Yaml();

    public VariableManager(Inventory inventory, Map<String, Object> extraVars) {
        this(inventory, extraVars, null);
    }

    public VariableManager(Inventory inventory, Map<String, Object> extraVars, Path baseDir) {
        this.inventory = inventory;
        this.extraVars = extraVars != null ? new HashMap<>(extraVars) : new HashMap<>();
        this.baseDir = baseDir;
    }

    /**
     * Registers a variable for a specific host.
     *
     * @param hostName The host name.
     * @param name     The variable name.
     * @param value    The variable value.
     */
    public void registerVariable(String hostName, String name, Object value) {
        hostVars.computeIfAbsent(hostName, k -> new HashMap<>()).put(name, value);
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
        Map<String, Object> hostVariables = hostVars.computeIfAbsent(hostName, k -> new HashMap<>());

        // Merge into top-level variables
        hostVariables.putAll(facts);

        // Merge into 'ansible_facts' dictionary
        @SuppressWarnings("unchecked")
        Map<String, Object> ansibleFacts = (Map<String, Object>) hostVariables.get("ansible_facts");
        if (ansibleFacts == null) {
            ansibleFacts = new HashMap<String, Object>();
            hostVariables.put("ansible_facts", ansibleFacts);
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
        Map<String, Object> vars = new HashMap<>(getAllVariables(null, new Host(hostName), null));
        vars.put("inventory_hostname", hostName);
        return vars;
    }

    /**
     * Resolves all variables for a given context (play, host, task).
     * Follows the priority defined in Variables-Templating.md.
     *
     * @param play The current play.
     * @param host The current host.
     * @param task The current task.
     * @return A merged map of all variables.
     */
    public Map<String, Object> getAllVariables(Play play, Host host, Task task) {
        Map<String, Object> variables = new HashMap<>();

        // Magic Variables
        if (host != null) {
            variables.put("inventory_hostname", host.name());
        }

        // 1. Role Defaults (Not implemented yet)

        // 2-5. Inventory Variables (all, parent group, child group, host vars)
        if (inventory != null && host != null) {
            variables.putAll(inventory.getVariablesForHost(host.name()));
        }

        // 6. Play Vars
        if (play != null) {
            variables.putAll(play.vars());
        }

        // 7. Play Vars Files
        if (play != null && !play.varsFiles().isEmpty()) {
            for (String varsFile : play.varsFiles()) {
                variables.putAll(loadVarsFile(varsFile));
            }
        }

        // 8. Role Vars (Not implemented yet)

        // 9. Task Vars
        if (task != null) {
            variables.putAll(task.vars());
        }

        // 10. Registered Variables and Facts (Higher priority than Task Vars in Ansible)
        if (host != null && hostVars.containsKey(host.name())) {
            variables.putAll(hostVars.get(host.name()));
        }

        // 11. Extra Vars (Highest priority)
        variables.putAll(extraVars);

        return Collections.unmodifiableMap(variables);
    }

    /**
     * Internal method to get registered and fact variables.
     */
    public Map<String, Object> getHostRuntimeVariables(String hostName) {
        return hostVars.getOrDefault(hostName, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadVarsFile(String varsFile) {
        Path filePath = baseDir != null ? baseDir.resolve(varsFile) : Path.of(varsFile);
        try (InputStream is = new FileInputStream(filePath.toFile())) {
            Object raw = yaml.load(is);
            if (raw instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (IOException e) {
            // In Ansible, if vars_file is not found, it's generally an error unless ignored.
            // For now, we'll just throw a runtime exception or log it.
            throw new RuntimeException("Failed to load vars_file: " + varsFile, e);
        }
        return Map.of();
    }
}
