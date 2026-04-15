package org.example.ansible.inventory;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

/**
 * Represents a single target host in the inventory.
 *
 * @param name      The hostname or IP address.
 * @param variables Host-specific variables.
 */
public record Host(String name, Map<String, Object> variables) {
    public Host(String name, Map<String, Object> variables) {
        this.name = name;
        this.variables = new HashMap<>(variables != null ? variables : Collections.emptyMap());
    }

    public Host(String name) {
        this(name, new HashMap<>());
    }
}
