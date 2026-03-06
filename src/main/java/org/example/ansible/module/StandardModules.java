package org.example.ansible.module;

import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.python.PythonModule;

import java.util.HashMap;
import java.util.Map;

/**
 * Standard Ansible modules registration.
 */
public class StandardModules {

    public static void registerAll(TaskExecutor executor) {
        ModuleRegistry registry = new ModuleRegistry();
        Connection localConnection = new LocalConnection(executor.getOsHandler());

        // 1. debug
        registry.register("debug", (args, becomeContext, pythonContext) -> {
            Object msg = args.getOrDefault("msg", "Hello world");
            return TaskResult.success(false, Map.of("msg", msg));
        });

        // 2. command
        registry.register("command", (args, becomeContext, pythonContext) -> {
            String command = (String) args.get("_raw_params");
            if (command == null) command = (String) args.get("cmd");
            if (command == null) return TaskResult.failure("no command given");

            ConnectionResult result = localConnection.execCommand(command, becomeContext);
            Map<String, Object> data = new HashMap<>();
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("rc", result.exitCode());
            data.put("changed", result.exitCode() == 0);

            if (result.exitCode() != 0) {
                return new TaskResult(false, false, "Command failed with rc " + result.exitCode(), data);
            }
            return TaskResult.success(data);
        });

        // 3. shell
        registry.register("shell", (args, becomeContext, pythonContext) -> {
            String command = (String) args.get("_raw_params");
            if (command == null) command = (String) args.get("cmd");
            if (command == null) return TaskResult.failure("no command given");

            ConnectionResult result = localConnection.execCommand(command, becomeContext);
            Map<String, Object> data = new HashMap<>();
            data.put("stdout", result.stdout());
            data.put("stderr", result.stderr());
            data.put("rc", result.exitCode());
            data.put("changed", result.exitCode() == 0);

            if (result.exitCode() != 0) {
                return new TaskResult(false, false, "Shell command failed with rc " + result.exitCode(), data);
            }
            return TaskResult.success(data);
        });

        // 4-7. copy, file, template, stat (delegated to PythonModule for name-based loading)
        registerPythonModule(registry, "copy");
        registerPythonModule(registry, "file");
        registerPythonModule(registry, "template");
        registerPythonModule(registry, "stat");

        registry.registerTo(executor);
    }

    private static void registerPythonModule(ModuleRegistry registry, String name) {
        PythonModule module = new PythonModule(name);
        registry.register(name, module);
        registry.register("ansible.builtin." + name, module);
    }
}
