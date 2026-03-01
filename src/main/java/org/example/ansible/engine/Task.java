package org.example.ansible.engine;

import java.util.Map;

/**
 * Represents an Ansible task.
 *
 * @param name   The name of the task.
 * @param action The module/action to execute.
 * @param args   The arguments for the module.
 */
public record Task(String name, String action, Map<String, Object> args) {
}
