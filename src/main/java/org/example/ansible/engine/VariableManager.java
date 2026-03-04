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
 * Manages variable resolution and priority.
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

        // Registered Variables (Higher priority than Task Vars in Ansible)
        if (host != null && hostVars.containsKey(host.name())) {
            variables.putAll(hostVars.get(host.name()));
        }

        // 10. Extra Vars
        variables.putAll(extraVars);

        return Collections.unmodifiableMap(variables);
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
