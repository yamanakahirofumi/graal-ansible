package org.example.ansible.engine;

import java.util.Map;

/**
 * Represents a Role reference in an Ansible Play.
 *
 * @param name The name of the role.
 * @param vars Variable overrides for this specific role instance (Level 20).
 */
public record Role(
        String name,
        Map<String, Object> vars
) {
    public Role(String name) {
        this(name, Map.of());
    }
}
