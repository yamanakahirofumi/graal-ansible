package org.example.ansible.plugin;

import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;

import java.util.Map;

/**
 * Interface for Action Plugins that run on the Control Node.
 */
public interface ActionPlugin {
    /**
     * Executes the action plugin.
     *
     * @param task          The task being executed.
     * @param variables     Current resolved variables.
     * @param taskExecutor  The task executor.
     * @return The task result.
     */
    TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor);
}
