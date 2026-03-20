package org.example.ansible.plugin;

import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in implementation of the 'debug' action plugin.
 */
public class DebugAction implements ActionPlugin {
    @Override
    public TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor) {
        Map<String, Object> args = task.args();
        Map<String, Object> data = new HashMap<>();

        if (args.containsKey("var")) {
            String varName = (String) args.get("var");
            Object value = taskExecutor.getVariableResolver().resolveValue("{{ " + varName + " }}", variables);
            data.put(varName, value);
        } else {
            String msg = (String) args.getOrDefault("msg", "Hello world!");
            data.put("msg", msg);
        }

        return TaskResult.success(false, data);
    }
}
