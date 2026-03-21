package org.example.ansible.plugin;

import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Built-in implementation of the 'copy' action plugin.
 */
public class CopyAction implements ActionPlugin {
    @Override
    public TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor) {
        Map<String, Object> args = task.args();
        String dest = (String) args.get("dest");
        Object content = args.get("content");
        String src = (String) args.get("src");

        if (dest == null) {
            return TaskResult.failure("dest is required for copy module");
        }

        Path localPath = null;
        boolean isTempFile = false;

        try {
            if (content != null) {
                String contentStr;
                if (content instanceof String s) {
                    contentStr = s;
                } else {
                    contentStr = content.toString();
                }
                localPath = Files.createTempFile("ansible-copy-content-", ".tmp");
                Files.writeString(localPath, contentStr, StandardCharsets.UTF_8);
                isTempFile = true;
            } else if (src != null) {
                String resolvedSrc = taskExecutor.resolveLocalPath(src);
                localPath = Path.of(resolvedSrc);
                if (!Files.exists(localPath)) {
                    return TaskResult.failure("Source file not found: " + resolvedSrc);
                }
            } else {
                return TaskResult.failure("Either src or content is required for copy module");
            }

            // Perform the file transfer
            org.example.ansible.connection.Connection connection = org.example.ansible.engine.TaskExecutor.getCurrentConnection();
            if (connection == null) {
                return TaskResult.failure("No active connection for file transfer");
            }

            connection.putFile(localPath, dest);

            // Handle file attributes (mode, owner, group)
            Map<String, Object> fileArgs = new HashMap<>();
            fileArgs.put("path", dest);
            fileArgs.put("state", "file");
            if (args.containsKey("mode")) fileArgs.put("mode", args.get("mode"));
            if (args.containsKey("owner")) fileArgs.put("owner", args.get("owner"));
            if (args.containsKey("group")) fileArgs.put("group", args.get("group"));

            if (fileArgs.size() > 2) {
                Map<String, Object> fileResult = taskExecutor.execute_from_python("file", fileArgs, variables);
                // Merge results if necessary, but primarily we want the changed status
                Map<String, Object> combinedData = new HashMap<>(fileResult);
                combinedData.put("dest", dest);
                combinedData.put("changed", true); // Transfer always means changed for now (simplification)
                return TaskResult.success(combinedData);
            }

            return TaskResult.success(true, Map.of("dest", dest, "changed", true));

        } catch (IOException e) {
            return TaskResult.failure("Copy operation failed: " + e.getMessage());
        } finally {
            if (isTempFile && localPath != null) {
                try {
                    Files.deleteIfExists(localPath);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
