package org.example.ansible.plugin;

import org.example.ansible.engine.ITaskExecutor;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Built-in implementation of the 'template' action plugin.
 */
public class TemplateAction implements ActionPlugin {
    @Override
    public TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor) {
        Map<String, Object> args = task.args();
        String src = (String) args.get("src");
        String dest = (String) args.get("dest");

        if (src == null || dest == null) {
            return TaskResult.failure("src and dest are required for template module");
        }

        Path localTempFile = null;
        try {
            // Resolve the source template path
            String resolvedSrc = taskExecutor.resolveLocalPath(src);
            Path templatePath = Path.of(resolvedSrc);
            if (!Files.exists(templatePath)) {
                return TaskResult.failure("Template file not found: " + resolvedSrc);
            }

            // Read template content
            String content = Files.readString(templatePath, StandardCharsets.UTF_8);

            // Render template via VariableResolver
            Object rendered = taskExecutor.getVariableResolver().resolveValue(content, variables);
            String renderedStr = rendered != null ? rendered.toString() : "";

            // Write rendered content to a temporary file
            localTempFile = Files.createTempFile("ansible-template-", ".tmp");
            Files.writeString(localTempFile, renderedStr, StandardCharsets.UTF_8);

            // Delegate transfer and attribute setting to helper
            return ActionPluginHelper.transferAndSetAttributes(task, dest, localTempFile, taskExecutor, variables);

        } catch (IOException e) {
            return TaskResult.failure("Template operation failed: " + e.getMessage());
        } finally {
            if (localTempFile != null) {
                try {
                    Files.deleteIfExists(localTempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
