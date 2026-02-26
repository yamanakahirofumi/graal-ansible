package org.example.ansible.engine;

import org.example.ansible.module.Module;
import java.util.Map;
import java.util.HashMap;

/**
 * Executes individual tasks by delegating to modules.
 */
public class TaskExecutor {

    private final Map<String, Module> modules = new HashMap<>();

    /**
     * Registers a module for a specific action name.
     *
     * @param action The action name (e.g., "debug", "command").
     * @param module The module implementation.
     */
    public void registerModule(String action, Module module) {
        modules.put(action, module);
    }

    /**
     * Executes the given task.
     *
     * @param task The task to execute.
     * @return The execution result.
     */
    public TaskResult execute(Task task) {
        Module module = modules.get(task.action());
        if (module == null) {
            return TaskResult.failure("Module not found: " + task.action());
        }
        try {
            return module.execute(task.args());
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }
}
