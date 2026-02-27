package org.example.ansible.engine;

import java.util.List;

/**
 * Represents a Play in an Ansible Playbook.
 *
 * @param name  The name of the play.
 * @param hosts The hosts this play should run on.
 * @param tasks The list of tasks to execute in this play.
 */
public record Play(String name, String hosts, List<Task> tasks) {
}
