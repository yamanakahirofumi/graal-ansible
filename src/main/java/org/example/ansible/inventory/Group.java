package org.example.ansible.inventory;

import java.util.List;
import java.util.Map;
import java.util.Collections;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Represents a group of hosts in the inventory.
 *
 * @param name      The name of the group.
 * @param hosts     The list of hosts belonging to this group.
 * @param children  The list of child groups.
 * @param variables Group-level variables.
 */
public record Group(
        String name,
        List<Host> hosts,
        List<Group> children,
        Map<String, Object> variables
) {
    public Group(String name, List<Host> hosts, List<Group> children, Map<String, Object> variables) {
        this.name = name;
        this.hosts = new ArrayList<>(hosts != null ? hosts : Collections.emptyList());
        this.children = new ArrayList<>(children != null ? children : Collections.emptyList());
        this.variables = new HashMap<>(variables != null ? variables : Collections.emptyMap());
    }

    public Group(String name) {
        this(name, new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    }
}
