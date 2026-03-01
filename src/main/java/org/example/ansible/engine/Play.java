package org.example.ansible.engine;

import java.util.List;
import java.util.Map;

/**
 * Represents a Play in an Ansible Playbook.
 *
 * @param name  The name of the play.
 * @param hosts The hosts this play should run on.
 * @param tasks The list of tasks to execute in this play.
 * @param vars  The variables defined for this play.
 */
public record Play(String name, String hosts, List<Task> tasks, Map<String, Object> vars) {
    public Play(String name, String hosts, List<Task> tasks) {
        this(name, hosts, tasks, Map.of());
    }
}
