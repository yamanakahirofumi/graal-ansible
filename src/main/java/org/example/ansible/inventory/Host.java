package org.example.ansible.inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a single target host in the inventory.
 *
 * @param name      The hostname or IP address.
 * @param variables Host-specific variables.
 */
public record Host(String name, Map<String, Object> variables) {
    public Host {
        variables = variables == null ? new HashMap<>() : new HashMap<>(variables);
    }

    public Host(String name) {
        this(name, new HashMap<>());
    }
}
