package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;
import org.graalvm.polyglot.Context;

import java.util.Map;
import java.util.HashMap;

/**
 * Executes individual tasks by delegating to modules.
 */
public class TaskExecutor implements AutoCloseable {

    private final Map<String, Module> modules = new HashMap<>();
    private final OSHandler osHandler;
    private final Context context;

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this.osHandler = osHandler;
        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.IsolateNativeModules", "true");

        // Enable native POSIX backend only on non-Windows systems for better file handling compatibility
        if (!"Windows".equals(osHandler.getOSFamily())) {
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

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }
}
