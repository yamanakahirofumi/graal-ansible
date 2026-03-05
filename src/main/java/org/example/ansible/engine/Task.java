package org.example.ansible.engine;

import java.util.Map;
import java.util.List;

/**
 * Represents an Ansible task.
 *
 * @param name          The name of the task.
 * @param action        The module/action to execute.
 * @param args          The arguments for the module.
 * @param vars          The variables defined for this task.
 * @param when          Conditional execution expression (String or List of Strings).
 * @param register      Variable name to register the result.
 * @param loop          Loop items or expression.
 * @param notifications List of handler names to notify on change.
 * @param failedWhen    Custom condition for failure.
 * @param changedWhen   Custom condition for change.
 * @param ignoreErrors  Whether to ignore errors for this task.
 * @param until         Retry until this condition is true.
 * @param retries       Maximum number of retries.
 * @param delay         Delay between retries in seconds.
 * @param delegateTo    Host to delegate the task to.
 * @param delegateFacts Whether to assign collected facts to the original host when using delegate_to.
 * @param runOnce       Whether to run the task only once per play.
 * @param ignoreUnreachable Whether to ignore unreachable hosts.
 * @param block         List of tasks to execute in a block.
 * @param rescue        List of tasks to execute if block fails.
 * @param always        List of tasks to execute regardless of block result.
 */
public record Task(
        String name,
        String action,
        Map<String, Object> args,
        Map<String, Object> vars,
        Object when,
        String register,
        Object loop,
        List<String> notifications,
        Object failedWhen,
        Object changedWhen,
        boolean ignoreErrors,
        Object until,
        Integer retries,
        Integer delay,
        String delegateTo,
        boolean delegateFacts,
        boolean runOnce,
        boolean ignoreUnreachable,
        List<Task> block,
        List<Task> rescue,
        List<Task> always
) {
    public Task(String name, String action, Map<String, Object> args) {
        this(name, action, args, Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of());
    }

    public Task(String name, String action, Map<String, Object> args, Map<String, Object> vars) {
        this(name, action, args, vars, null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of());
    }
}
