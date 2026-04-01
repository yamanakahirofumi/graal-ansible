package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionFactory;
import org.example.ansible.inventory.Host;
import org.example.ansible.util.OSHandler;

import java.util.Map;

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
     * @param blockVars        Accumulated block variables.
     * @return The execution result.
     */
    TaskResult execute(Play play, Host host, Task task, VariableManager variableManager, boolean inheritedCheckMode, Object inheritedEnvironment, Map<String, Object> blockVars, Connection connection, ConnectionFactory connectionFactory);

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

    /**
     * Gets the variable resolver used by the executor.
     * @return The VariableResolver.
     */
    VariableResolver getVariableResolver();

    /**
     * Gets the variable manager currently used.
     * @return The VariableManager.
     */
    VariableManager getVariableManager();

    /**
     * Resolves a local path relative to the playbook's base directory.
     * @param path The path to resolve.
     * @return The absolute path as a String.
     */
    String resolveLocalPath(String path);

    /**
     * Python (Action Plugin) から呼び出され、指定されたモジュールを実行します。
     * @param moduleName モジュール名 (例: "copy", "apt")
     * @param moduleArgs モジュール引数 (Map形式)
     * @param taskVars 現在のタスク変数
     * @return 実行結果 (Map形式、Ansible互換の辞書)
     */
    Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars);

    @Override
    void close();
}
