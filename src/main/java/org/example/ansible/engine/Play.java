package org.example.ansible.engine;

import java.util.List;
import java.util.Map;

/**
 * Represents a Play in an Ansible Playbook.
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
        Object serial,
        String strategy
) {
    public Play {
        if (vars == null) vars = Map.of();
        if (varsFiles == null) varsFiles = List.of();
        if (varsPrompt == null) varsPrompt = List.of();
        if (roles == null) roles = List.of();
        if (handlers == null) handlers = List.of();
        if (tags == null) tags = List.of();
        if (strategy == null) strategy = "linear";
    }

    public Play(String name, String hosts, List<Task> tasks) {
        this(name, hosts, tasks, Map.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, null, "linear");
    }

    public Play(String name, String hosts, List<Task> tasks, Map<String, Object> vars) {
        this(name, hosts, tasks, vars, List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, null, List.of(), null, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, List.of(), List.of(), handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, List.of(), null, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, List.of(), List.of(), handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, List.of(), roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, null, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, anyErrorsFatal, null, "linear");
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
        this(name, hosts, tasks, vars, varsFiles, varsPrompt, roles, handlers, become, becomeMethod, becomeUser, becomeFlags, checkMode, environment, tags, anyErrorsFatal, null, strategy);
    }
}
