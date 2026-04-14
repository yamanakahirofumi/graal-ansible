package org.example.ansible.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Objects;

/**
 * Represents a group of hosts in the inventory.
 */
public record Group(
        String name,
        List<Host> hosts,
        List<Group> children,
        Map<String, Object> variables
) {
    public Group(String name, List<Host> hosts, List<Group> children, Map<String, Object> variables) {
        this.name = name;
        this.hosts = new ArrayList<>(hosts);
        this.children = new ArrayList<>(children);
        this.variables = new HashMap<>(variables);
    }

    public Group(String name) {
        this(name, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
    }

    public void addHost(Host host) {
        if (hosts.stream().noneMatch(h -> h.name().equals(host.name()))) {
            hosts.add(host);
        }
    }

    public void addChild(Group child) {
        if (children.stream().noneMatch(g -> g.name().equals(child.name()))) {
            children.add(child);
        }
    }

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Group group = (Group) o;
        return Objects.equals(name, group.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
