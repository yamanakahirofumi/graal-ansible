package org.example.ansible.plugin;

import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;

import java.util.Map;

/**
 * Built-in implementation of the 'set_fact' action plugin.
 */
public class SetFactAction implements ActionPlugin {
    @Override
    public TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor) {
        // All arguments to set_fact are treated as facts to be registered
        return TaskResult.success(false, Map.of("ansible_facts", task.args()));
    }
}
