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

    private final Map<String, Module> modules = new HashMap<>();
    private final OSHandler osHandler;
    private final Context context;

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this.osHandler = osHandler;

        Map<String, String> options = new HashMap<>();
        // Native/POSIX specific options are enabled only on Linux for maximum compatibility and performance.
        // On Windows and macOS, these can cause stability issues or are not supported.
        if ("Linux".equals(osHandler.getOSFamily())) {
            options.put("python.IsolateNativeModules", "true");
            options.put("python.PosixModuleBackend", "native");
        }

        // Set PYTHONPATH to include our site-packages so that respawned processes can find 'ansible'
        java.util.List<String> sitePackages = org.example.ansible.util.PythonEnv.getSitePackagesFromEnv();
        if (!sitePackages.isEmpty()) {
            String pythonPath = String.join(java.io.File.pathSeparator, sitePackages);
            options.put("python.PythonPath", pythonPath);
        }

        this.context = Context.newBuilder("python")
                .allowAllAccess(true)
                .options(options)
                .build();
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

    @Override
    public Module getModule(String action) {
        return modules.get(action);
    }

    /**
     * Executes the given task.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @return The execution result.
     */
    public TaskResult execute(Task task, BecomeContext becomeContext) {
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
     * @return The execution result.
     */
    public TaskResult execute(Task task, BecomeContext becomeContext, Connection connection) {
        setCurrentConnection(connection);
        try {
            return execute(task, becomeContext);
        } finally {
            clearCurrentConnection();
        }
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
