package org.example.ansible.inventory;

import java.util.Map;
import java.util.Collections;

/**
 * Represents a single target host in the inventory.
 *
 * @param name      The hostname or IP address.
 * @param variables Host-specific variables.
 */
public record Host(String name, Map<String, Object> variables) {
    public Host(String name) {
        this(name, Collections.emptyMap());
    }
}
