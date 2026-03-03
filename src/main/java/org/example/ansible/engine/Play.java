package org.example.ansible.engine;

import java.util.List;
import java.util.Map;

/**
 * Represents a Play in an Ansible Playbook.
 *
 * @param name      The name of the play.
 * @param hosts     The hosts this play should run on.
 * @param tasks     The list of tasks to execute in this play.
 * @param vars      The variables defined for this play.
 * @param varsFiles The list of variable files to include.
 */
public record Play(String name, String hosts, List<Task> tasks, Map<String, Object> vars, List<String> varsFiles) {
    public Play(String name, String hosts, List<Task> tasks) {
        this(name, hosts, tasks, Map.of(), List.of());
    }

    public Play(String name, String hosts, List<Task> tasks, Map<String, Object> vars) {
        this(name, hosts, tasks, vars, List.of());
    }
}
