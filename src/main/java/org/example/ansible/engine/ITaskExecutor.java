package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Host;
import org.example.ansible.util.OSHandler;

/**
 * Interface for executing tasks.
 */
public interface ITaskExecutor extends AutoCloseable {
    /**
     * Executes the given task.
     *
     * @param play             The play context.
     * @param host             The target host.
     * @param task             The task to execute.
     * @param variableManager  The variable manager.
     * @param inheritedCheckMode Inherited check mode.
     * @param inheritedEnvironment Inherited environment.
     * @return The execution result.
     */
    TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Connection connection, ConnectionFactory connectionFactory);

    /**
     * Executes the given task.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @param environment   The environment variables for the task.
     * @return The execution result.
     */
    TaskResult execute(Task task, BecomeContext becomeContext, java.util.Map<String, String> environment);

    /**
     * Executes the given task with a specific connection.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @param connection    The connection to the target host.
     * @param environment   The environment variables for the task.
     * @return The execution result.
     */
    TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, java.util.Map<String, String> environment);

    /**
     * Gets the OS handler used by the executor.
     * @return The OSHandler.
     */
    OSHandler getOsHandler();

    @Override
    void close();
}
