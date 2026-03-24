package org.example.ansible.module;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.graalvm.polyglot.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * CommandModule handles 'command' and 'shell' modules by executing commands on the target node.
 */
public class CommandModule implements Module {
    private final String actionName;

    public CommandModule(String actionName) {
        this.actionName = actionName;
    }

    @Override
    public TaskResult execute(Map<String, Object> args, BecomeContext becomeContext, Context context) {
        String command = (String) args.get("_raw_params");
        if (command == null) command = (String) args.get("cmd");
        if (command == null) return TaskResult.failure("no command given");

        Connection connection = TaskExecutor.getCurrentConnection();
        if (connection == null) {
            // Fallback to local connection if no connection is set (mostly for standalone module tests)
            connection = new LocalConnection();
        }

        ConnectionResult result = connection.execCommand(command, becomeContext, TaskExecutor.getCurrentEnvironment());

        Map<String, Object> data = new HashMap<>();
        data.put("stdout", result.stdout());
        data.put("stderr", result.stderr());
        data.put("rc", result.exitCode());
        data.put("changed", true); // Commands are generally assumed to have changed the system unless otherwise specified

        if (result.exitCode() != 0) {
            return new TaskResult(false, false, actionName + " failed with rc " + result.exitCode(), data);
        }
        return TaskResult.success(data);
    }
}
