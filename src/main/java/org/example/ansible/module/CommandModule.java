package org.example.ansible.module;

import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of the 'command' module.
 * Executes a command directly without a shell.
 */
public class CommandModule implements Module {
    private final LocalConnection connection = new LocalConnection();

    @Override
    public TaskResult execute(Map<String, Object> args) {
        String command = (String) args.get("_raw_params");
        if (command == null) {
            return TaskResult.failure("No command specified");
        }

        // In Ansible, the 'command' module does NOT run through a shell by default.
        // However, our LocalConnection.execCommand uses /bin/sh -c currently.
        // For 'command' module compatibility, we might want to change LocalConnection
        // or handle it here.
        // For now, we follow the existing LocalConnection implementation.

        ConnectionResult result = connection.execCommand(command, false);

        Map<String, Object> data = new HashMap<>();
        data.put("stdout", result.stdout());
        data.put("stderr", result.stderr());
        data.put("rc", result.exitCode());
        data.put("changed", true); // Executing a command is generally considered a change

        if (result.exitCode() != 0) {
            return new TaskResult(false, true, "Command failed with rc " + result.exitCode(), data);
        }

        return TaskResult.success(true, data);
    }
}
