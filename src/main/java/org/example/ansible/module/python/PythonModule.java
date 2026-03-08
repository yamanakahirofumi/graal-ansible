package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.example.ansible.util.PythonEnv;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.Source;

import java.util.Map;
import java.util.List;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PythonModule implements Module {
    private final String moduleName;
    private final String scriptContent; // Added back for mocking/legacy support
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PythonModule(String moduleName) {
        this(moduleName, null);
    }

    public PythonModule(String moduleName, String scriptContent) {
        this.moduleName = moduleName;
        this.scriptContent = scriptContent;
    }

    @Override
    public TaskResult execute(final Map<String, Object> args, BecomeContext becomeContext, Context context) {
        // Mock patchelf for GraalPy internal use on Linux
        try {
            if (System.getProperty("os.name").toLowerCase().contains("linux")) {
                File dummyPatchelf = new File("/tmp/patchelf");
                if (!dummyPatchelf.exists()) {
                    java.nio.file.Files.writeString(dummyPatchelf.toPath(), "#!/bin/sh\nexit 0\n");
                    dummyPatchelf.setExecutable(true);
                }
            }
        } catch (Exception ignored) {}

        try {
            // Setup site-packages info for Python
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();

            // Bind values to the Python context
            context.getBindings("python").putMember("complex_args_java", args);
            context.getBindings("python").putMember("module_name", moduleName);
            context.getBindings("python").putMember("site_packages_java", sitePackages);

            Source source;
            if (scriptContent != null) {
                // Legacy/Mock mode
                context.getBindings("python").putMember("module_code", scriptContent);
                source = loadResource("ansible_mock_launcher.py");
            } else {
                // Actual module mode
                source = loadResource("ansible_launcher.py");
            }

            context.eval(source);
            Value pythonResult = context.getBindings("python").getMember("result");

            if (pythonResult == null || !pythonResult.isString()) {
                return TaskResult.failure("Module produced no valid output");
            }
            final String output = pythonResult.asString();

            if (output == null || output.isBlank()) {
                return TaskResult.failure("Module produced no output");
            }

            String jsonOutput = output;
            if (output.contains("{")) {
                jsonOutput = output.substring(output.indexOf("{"));
            }

            @SuppressWarnings("unchecked")
            final Map<String, Object> resultMap = objectMapper.readValue(jsonOutput, Map.class);

            final boolean failed = Boolean.TRUE.equals(resultMap.get("failed"));
            if (failed) {
                return new TaskResult(false, false, resultMap.getOrDefault("msg", "Module failed").toString(), resultMap);
            }

            return TaskResult.success(resultMap);

        } catch (PolyglotException e) {
            return TaskResult.failure("GraalPy execution failed (PolyglotException): " + e.getMessage());
        } catch (Exception e) {
            return TaskResult.failure("GraalPy execution failed: " + e.getMessage());
        }
    }

    private Source loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                throw new IOException("Resource not found: " + name);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("python", content, name).build();
        }
    }
}
