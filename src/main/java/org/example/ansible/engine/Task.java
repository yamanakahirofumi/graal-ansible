package org.example.ansible.engine;

import java.util.Map;
import java.util.List;

/**
 * Represents an Ansible task.
 *
 * @param name       The name of the task.
 * @param action     The module/action to execute.
 * @param args       The arguments for the module.
 * @param vars       The variables defined for this task.
 * @param when       Conditional execution expression (String or List of Strings).
 * @param register   Variable name to register the result.
 * @param loop       Loop items or expression.
 * @param notifications List of handler names to notify on change.
 */
public record Task(
        String name,
        String action,
        Map<String, Object> args,
        Map<String, Object> vars,
        Object when,
        String register,
        Object loop,
        List<String> notifications
) {
    public Task(String name, String action, Map<String, Object> args) {
        this(name, action, args, Map.of(), null, null, null, List.of());
    }

    public Task(String name, String action, Map<String, Object> args, Map<String, Object> vars) {
        this(name, action, args, vars, null, null, null, List.of());
    }
}
