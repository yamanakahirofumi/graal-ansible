package org.example.ansible.module;

import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of the 'shell' module.
 * Executes a command through a shell.
 */
public class ShellModule implements Module {
    private final LocalConnection connection = new LocalConnection();

    @Override
    public TaskResult execute(Map<String, Object> args) {
        String command = (String) args.get("_raw_params");
        if (command == null) {
            return TaskResult.failure("No command specified");
        }

        ConnectionResult result = connection.execCommand(command, false);

        Map<String, Object> data = new HashMap<>();
        data.put("stdout", result.stdout());
        data.put("stderr", result.stderr());
        data.put("rc", result.exitCode());
        data.put("changed", true);

        if (result.exitCode() != 0) {
            return new TaskResult(false, true, "Shell command failed with rc " + result.exitCode(), data);
        }

        return TaskResult.success(true, data);
    }
}
