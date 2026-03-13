package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.module.Module;
import org.example.ansible.util.OSHandler;

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
     * Gets the module implementation for a specific action.
     *
     * @param action The action name.
     * @return The module implementation, or null if not found.
     */
    Module getModule(String action);

    @Override
    void close();
}
