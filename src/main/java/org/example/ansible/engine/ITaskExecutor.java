package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.util.OSHandler;
import java.util.Map;

/**
 * Interface for executing tasks.
 */
public interface ITaskExecutor extends AutoCloseable {
    /**
     * Executes the given task.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @return The execution result.
     */
    TaskResult execute(Task task, BecomeContext becomeContext);

    /**
     * Executes the given task with a specific connection.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @param connection    The connection to the target host.
     * @return The execution result.
     */
    TaskResult execute(Task task, BecomeContext becomeContext, Connection connection);

    /**
     * Gets the OS handler used by the executor.
     * @return The OSHandler.
     */
    OSHandler getOsHandler();

    /**
     * Executes a module by name with the given arguments.
     * Used mainly by action plugins.
     *
     * @param moduleName The name of the module.
     * @param moduleArgs The arguments for the module.
     * @return The execution result.
     */
    default TaskResult executeModule(String moduleName, Map<String, Object> moduleArgs) {
        return TaskResult.failure("executeModule not implemented");
    }

    /**
     * Sets the current task variables.
     * @param taskVars The task variables.
     */
    default void setCurrentTaskVars(Map<String, Object> taskVars) {}

    /**
     * Gets the current task variables.
     * @return The current task variables.
     */
    default Map<String, Object> getCurrentTaskVars() {
        return Map.of();
    }

    @Override
    void close();
}
