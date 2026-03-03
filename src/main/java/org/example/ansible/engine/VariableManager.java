package org.example.ansible.engine;

import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages variable resolution and priority.
 */
public class VariableManager {
    private final Inventory inventory;
    private final Map<String, Object> extraVars;

    public VariableManager(Inventory inventory, Map<String, Object> extraVars) {
        this.inventory = inventory;
        this.extraVars = extraVars != null ? new HashMap<>(extraVars) : new HashMap<>();
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

        // 7. Play Vars Files (Not implemented yet)

        // 8. Role Vars (Not implemented yet)

        // 9. Task Vars
        if (task != null) {
            variables.putAll(task.vars());
        }

        // 10. Extra Vars
        variables.putAll(extraVars);

        return Collections.unmodifiableMap(variables);
    }
}
