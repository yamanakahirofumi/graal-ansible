package org.example.ansible.engine;

import java.util.List;
import java.util.Map;

/**
 * Represents a Play in an Ansible Playbook.
 *
 * @param name            The name of the play.
 * @param hosts           The hosts this play should run on.
 * @param tasks           The list of tasks to execute in this play.
 * @param vars            The variables defined for this play.
 * @param varsFiles       The list of variable files to include.
 * @param varsPrompt      The list of interactive variable prompts.
 * @param roles           The list of roles to include in this play.
 * @param handlers        The list of handlers defined for this play.
 * @param become          Whether to enable privilege escalation.
 * @param becomeMethod    The privilege escalation method (e.g., sudo, su).
 * @param becomeUser      The user to become.
 * @param becomeFlags     Additional flags for privilege escalation.
 * @param checkMode       Whether to run this play in check mode.
 * @param environment     Environment variables for this play.
 * @param tags            The tags defined for this play.
 * @param anyErrorsFatal  Whether to halt play execution on any host failure.
 * @param strategy        The execution strategy (e.g., linear, free).
 * @param serial          The number or percentage of hosts to execute at once.
 * @param throttle        The maximum number of hosts to execute tasks on in parallel.
 */
public record Play(
        String name,
        String hosts,
        List<Task> tasks,
        Map<String, Object> vars,
        List<String> varsFiles,
        List<Map<String, Object>> varsPrompt,
        List<Role> roles,
        List<Task> handlers,
        Object become,
        String becomeMethod,
        String becomeUser,
        String becomeFlags,
        Object checkMode,
        Object environment,
        List<String> tags,
        Object anyErrorsFatal,
        String strategy,
        Object serial,
        Object throttle
) {
    public Play(String name, String hosts, List<Task> tasks) {
        this(name, hosts, tasks, Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", null, null);
    }

    public Play(String name, String hosts, List<Task> tasks, Map<String, Object> vars) {
        this(name, hosts, tasks, vars, List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment
    ) {
        this(name, hosts, tasks, vars, varsFiles, List.of(), List.of(), handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, List.of(), null, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags
    ) {
        this(name, hosts, tasks, vars, varsFiles, List.of(), List.of(), handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Role> roles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags
    ) {
        this(name, hosts, tasks, vars, varsFiles, List.of(), roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Map<String, Object>> varsPrompt,
            List<Role> roles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags
    ) {
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Map<String, Object>> varsPrompt,
            List<Role> roles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags,
            Object anyErrorsFatal
    ) {
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, anyErrorsFatal, "linear", null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Map<String, Object>> varsPrompt,
            List<Role> roles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags,
            Object anyErrorsFatal,
            String strategy
    ) {
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, anyErrorsFatal, strategy, null, null);
    }

    public Play(
            String name,
            String hosts,
            List<Task> tasks,
            Map<String, Object> vars,
            List<String> varsFiles,
            List<Map<String, Object>> varsPrompt,
            List<Role> roles,
            List<Task> handlers,
            Object become,
            String becomeMethod,
            String becomeUser,
            String becomeFlags,
            Object checkMode,
            Object environment,
            List<String> tags,
            Object anyErrorsFatal,
            String strategy,
            Object serial
    ) {
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, anyErrorsFatal, strategy, serial, null);
    }
}
