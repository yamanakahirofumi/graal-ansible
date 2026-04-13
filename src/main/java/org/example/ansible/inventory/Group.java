package org.example.ansible.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents a group of hosts in the inventory.
 */
public class Group {
    private final String name;
    private final List<Host> hosts;
    private final List<Group> children;
    private final Map<String, Object> variables;

    public Group(String name, List<Host> hosts, List<Group> children, Map<String, Object> variables) {
        this.name = name;
        this.hosts = hosts != null ? new ArrayList<>(hosts) : new ArrayList<>();
        this.children = children != null ? new ArrayList<>(children) : new ArrayList<>();
        this.variables = variables != null ? new HashMap<>(variables) : new HashMap<>();
    }

    public Group(String name) {
        this(name, new ArrayList<>(), new ArrayList<>(), new HashMap<>());
    }

    public String name() {
        return name;
    }

    public List<Host> hosts() {
        return hosts;
    }

    public List<Group> children() {
        return children;
    }

    public Map<String, Object> variables() {
        return variables;
    }

    /**
     * Adds a host to this group if it's not already present.
     */
    public void addHost(Host host) {
        if (hosts.stream().noneMatch(h -> h.name().equals(host.name()))) {
            hosts.add(host);
        }
    }

    /**
     * Adds a child group to this group if it's not already present.
     */
    public void addChild(Group group) {
        if (children.stream().noneMatch(g -> g.name().equals(group.name()))) {
            children.add(group);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Group group = (Group) o;
        return java.util.Objects.equals(name, group.name);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Group[name=" + name + "]";
    }
}
