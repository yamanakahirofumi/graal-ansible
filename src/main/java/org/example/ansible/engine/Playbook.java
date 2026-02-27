package org.example.ansible.engine;

import java.util.List;

/**
 * Represents an Ansible Playbook, which is a list of Plays.
 *
 * @param plays The list of plays in this playbook.
 */
public record Playbook(List<Play> plays) {
}
