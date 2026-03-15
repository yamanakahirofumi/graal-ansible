package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.module.Module;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;
import org.graalvm.polyglot.Context;

import java.util.Map;
import java.util.HashMap;

/**
 * Executes individual tasks by delegating to modules.
 */
public class TaskExecutor implements ITaskExecutor {

    private static final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<String, String>> currentEnvironment = new ThreadLocal<>();

    /**
     * Sets the connection for the current thread.
     * @param connection The connection to set.
     */
    public static void setCurrentConnection(Connection connection) {
        currentConnection.set(connection);
    }

    /**
     * Gets the connection for the current thread.
     * @return The current connection.
     */
    public static Connection getCurrentConnection() {
        return currentConnection.get();
    }

    /**
     * Clears the connection for the current thread.
     */
    public static void clearCurrentConnection() {
        currentConnection.remove();
    }

    /**
     * Sets the environment for the current thread.
     * @param environment The environment to set.
     */
    public static void setCurrentEnvironment(java.util.Map<String, String> environment) {
        currentEnvironment.set(environment);
    }

    /**
     * Gets the environment for the current thread.
     * @return The current environment.
     */
    public static java.util.Map<String, String> getCurrentEnvironment() {
        return currentEnvironment.get();
    }

    /**
     * Clears the environment for the current thread.
     */
    public static void clearCurrentEnvironment() {
        currentEnvironment.remove();
    }

    private final Map<String, Module> modules = new HashMap<>();
    private final OSHandler osHandler;
    private final Context context;

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this.osHandler = osHandler;
        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true);

        // Native/POSIX specific options are enabled only on Linux for maximum compatibility and performance.
        // On Windows and macOS, these can cause stability issues or are not supported.
        if ("Linux".equals(osHandler.getOSFamily())) {
            builder.option("python.IsolateNativeModules", "true");
            builder.option("python.PosixModuleBackend", "native");
        }

        this.context = builder.build();
    }

    /**
     * Gets the OS handler used by the executor.
     * @return The OSHandler.
     */
    public OSHandler getOsHandler() {
        return osHandler;
    }

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
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @param environment   The environment variables for the task.
     * @return The execution result.
     */
    public TaskResult execute(Task task, BecomeContext becomeContext, java.util.Map<String, String> environment) {
        Module module = modules.get(task.action());
        if (module == null) {
            return TaskResult.failure("Module not found: " + task.action());
        }
        try {
            return module.execute(task.args(), becomeContext, context);
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Executes the given task with a specific connection.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @param connection    The connection to the target host.
     * @param environment   The environment variables for the task.
     * @return The execution result.
     */
    public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection, java.util.Map<String, String> environment) {
        setCurrentConnection(connection);
        setCurrentEnvironment(environment);
        try {
            return execute(task, becomeContext, environment);
        } finally {
            clearCurrentConnection();
            clearCurrentEnvironment();
        }
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
