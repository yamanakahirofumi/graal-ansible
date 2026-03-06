package org.example.ansible.module;

import org.example.ansible.engine.TaskExecutor;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry for Ansible modules.
 */
public class ModuleRegistry {
    private final Map<String, Module> modules = new HashMap<>();

    public void register(String name, Module module) {
        modules.put(name, module);
    }

    public void registerTo(TaskExecutor executor) {
        modules.forEach(executor::registerModule);
    }

    public Map<String, Module> getModules() {
        return new HashMap<>(modules);
    }
}
