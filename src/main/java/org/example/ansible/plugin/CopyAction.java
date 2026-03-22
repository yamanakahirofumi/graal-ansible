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

            return ActionPluginHelper.transferAndSetAttributes(task, dest, localPath, taskExecutor, variables);

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
