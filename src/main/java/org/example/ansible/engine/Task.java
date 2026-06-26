package org.example.ansible.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * Represents an Ansible task.
 *
 * @param name              The name of the task.
 * @param action            The module/action to execute.
 * @param args              The arguments for the module.
 * @param vars              The variables defined for this task.
 * @param when              Conditional execution expression (String or List of Strings).
 * @param register          Variable name to register the result.
 * @param loop              Loop items or expression.
 * @param loopControl       Loop control parameters (index_var, loop_var, label, pause).
 * @param notifications     List of handler names to notify on change.
 * @param failedWhen        Custom condition for failure.
 * @param changedWhen       Custom condition for change.
 * @param ignoreErrors      Whether to ignore errors for this task.
 * @param until             Retry until this condition is true.
 * @param retries           Maximum number of retries.
 * @param delay             Delay between retries in seconds.
 * @param delegateTo        Host to delegate the task to.
 * @param delegateFacts     Whether to assign collected facts to the original host when using delegate_to.
 * @param runOnce           Whether to run the task only once per play.
 * @param ignoreUnreachable Whether to ignore unreachable hosts.
 * @param block             List of tasks to execute in a block.
 * @param rescue            List of tasks to execute if block fails.
 * @param always            List of tasks to execute regardless of block result.
 * @param become            Whether to enable privilege escalation.
 * @param becomeMethod      The privilege escalation method (e.g., sudo, su).
 * @param becomeUser        The user to become.
 * @param becomeFlags       Additional flags for privilege escalation.
 * @param checkMode         Whether to run this task in check mode.
 * @param environment       Environment variables for this task.
 * @param tags              The tags defined for this task.
 * @param listen            List of topics this handler listens to.
 * @param anyErrorsFatal    Whether to halt play execution on any host failure.
 * @param asyncVal          The timeout for asynchronous execution (seconds).
 * @param poll              The polling interval for asynchronous execution (seconds).
 */
public record Task(
        String name,
        String action,
        Map<String, Object> args,
        Map<String, Object> vars,
        Object when,
        String register,
        Object loop,
        Map<String, Object> loopControl,
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
        List<Task> always,
        Object become,
        String becomeMethod,
        String becomeUser,
        String becomeFlags,
        Object checkMode,
        Object environment,
        List<String> tags,
        List<String> listen,
        Object anyErrorsFatal,
        Integer asyncVal,
        Integer poll
) {
    public Task(String name, String action, Map<String, Object> args) {
        this(name, action, args, new HashMap<>(), null, null, null, Map.of(), new ArrayList<>(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null, 0, 10);
    }

    public Task(String name, String action, Map<String, Object> args, Map<String, Object> vars) {
        this(name, action, args, vars, null, null, null, Map.of(), List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, null, null, List.of(), List.of(), null, 0, 10);
    }

    public Task(
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
            List<Task> always,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment
    ) {
        this(name, action, args, vars, when, register, loop, Map.of(), notifications, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
                become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, List.of(), List.of(), null, 0, 10);
    }

    public Task(
            String name,
            String action,
            Map<String, Object> args,
            Map<String, Object> vars,
            Object when,
            String register,
            Object loop,
            Map<String, Object> loopControl,
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
            List<Task> always,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags
    ) {
        this(name, action, args, vars, when, register, loop, loopControl, notifications, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
                become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, List.of(), null, 0, 10);
    }

    public Task(
            String name,
            String action,
            Map<String, Object> args,
            Map<String, Object> vars,
            Object when,
            String register,
            Object loop,
            Map<String, Object> loopControl,
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
            List<Task> always,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags,
            List<String> listen
    ) {
        this(name, action, args, vars, when, register, loop, loopControl, notifications, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
                become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, listen, null, 0, 10);
    }

    public Task(
            String name,
            String action,
            Map<String, Object> args,
            Map<String, Object> vars,
            Object when,
            String register,
            Object loop,
            Map<String, Object> loopControl,
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
            List<Task> always,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags,
            List<String> listen,
            Object anyErrorsFatal
    ) {
        this(name, action, args, vars, when, register, loop, loopControl, notifications, failedWhen, changedWhen, ignoreErrors,
                until, retries, delay, delegateTo, delegateFacts, runOnce, ignoreUnreachable, block, rescue, always,
                become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, listen, anyErrorsFatal, 0, 10);
    }
}
