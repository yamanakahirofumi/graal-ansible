package org.example.ansible.plugin;

import org.example.ansible.connection.Connection;
import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.TaskExecutor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for Action Plugins to perform common operations.
 */
public class ActionPluginHelper {

    /**
     * Transfers a file to the remote host and sets its attributes.
     *
     * @param task         The original task containing attribute arguments (mode, owner, group).
     * @param dest         The destination path on the remote host.
     * @param localPath    The local source path.
     * @param taskExecutor The task executor to run the 'file' module.
     * @param variables    The current variables for module execution.
     * @return A TaskResult indicating success or failure.
     */
    public static TaskResult transferAndSetAttributes(Task task, String dest, Path localPath, ITaskExecutor taskExecutor, Map<String, Object> variables) {
        Connection connection = TaskExecutor.getCurrentConnection();
        if (connection == null) {
            return TaskResult.failure("No active connection for file transfer");
        }

        try {
            connection.putFile(localPath, dest);

            // Handle file attributes (mode, owner, group)
            Map<String, Object> fileArgs = new HashMap<>();
            fileArgs.put("path", dest);
            fileArgs.put("state", "file");

            Map<String, Object> taskArgs = task.args();
            if (taskArgs.containsKey("mode")) fileArgs.put("mode", taskArgs.get("mode"));
            if (taskArgs.containsKey("owner")) fileArgs.put("owner", taskArgs.get("owner"));
            if (taskArgs.containsKey("group")) fileArgs.put("group", taskArgs.get("group"));

            if (fileArgs.size() > 2) {
                Map<String, Object> fileResult = taskExecutor.execute_from_python("file", fileArgs, variables);
                Map<String, Object> combinedData = new HashMap<>(fileResult);
                combinedData.put("dest", dest);
                combinedData.put("changed", true);
                return TaskResult.success(combinedData);
            }

            return TaskResult.success(true, Map.of("dest", dest, "changed", true));
        } catch (Exception e) {
            return TaskResult.failure("File transfer or attribute setting failed: " + e.getMessage());
        }
    }
}
