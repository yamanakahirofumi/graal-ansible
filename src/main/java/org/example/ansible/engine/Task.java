package org.example.ansible.engine;

import java.util.Map;
import java.util.List;

/**
 * Represents an Ansible task.
 *
 * @param name     The name of the task.
 * @param action   The module/action to execute.
 * @param args     The arguments for the module.
 * @param vars     The variables defined for this task.
 * @param when     Conditional execution expression.
 * @param register Variable name to register the result.
 * @param loop     Loop items or expression.
 */
public record Task(
        String name,
        String action,
        Map<String, Object> args,
        Map<String, Object> vars,
        String when,
        String register,
        Object loop
) {
    public Task(String name, String action, Map<String, Object> args) {
        this(name, action, args, Map.of(), null, null, null);
    }

    public Task(String name, String action, Map<String, Object> args, Map<String, Object> vars) {
        this(name, action, args, vars, null, null, null);
    }
}
