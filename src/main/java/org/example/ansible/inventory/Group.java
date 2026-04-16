package org.example.ansible.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public Group {
        hosts = hosts == null ? new ArrayList<>() : new ArrayList<>(hosts);
        children = children == null ? new ArrayList<>() : new ArrayList<>(children);
        variables = variables == null ? new HashMap<>() : new HashMap<>(variables);
    }

    public Group(String name) {
        this(name, new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    }
}
