package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.module.Module;
import org.example.ansible.util.PythonEnv;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.Source;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Base64;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Connection connection = TaskExecutor.getCurrentConnection();

        if (connection != null && !(connection instanceof LocalConnection)) {
            return executeRemotely(args, becomeContext, connection);
        }

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
            context.getBindings("python").putMember("connection_java", connection);
            context.getBindings("python").putMember("become_context_java", becomeContext);

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

    private TaskResult executeRemotely(Map<String, Object> args, BecomeContext becomeContext, Connection connection) {
        File moduleFile = findModuleFile().orElse(null);
        if (moduleFile == null) {
            return TaskResult.failure("Module source not found: " + moduleName);
        }

        String remoteTmpDir = "/tmp/ansible." + UUID.randomUUID();
        try {
            String wrappedScript = wrapModule(moduleFile, args);

            // Create remote temp dir
            var mkdirRes = connection.execCommand("mkdir -p " + remoteTmpDir, becomeContext);
            if (mkdirRes.exitCode() != 0) {
                return TaskResult.failure("Failed to create remote temp dir: " + mkdirRes.stderr());
            }

            // Transfer module
            String remoteModulePath = remoteTmpDir + "/" + moduleName + ".py";
            Path localTempFile = Files.createTempFile("ansible-module-", ".py");
            try {
                Files.writeString(localTempFile, wrappedScript, StandardCharsets.UTF_8);
                connection.putFile(localTempFile, remoteModulePath);
            } finally {
                Files.deleteIfExists(localTempFile);
            }

            // Execute remotely
            var execRes = connection.execCommand("python3 " + remoteModulePath, becomeContext);
            String output = execRes.stdout();

            if (output == null || output.isBlank()) {
                return TaskResult.failure("Module produced no output: " + execRes.stderr());
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
        } catch (IOException e) {
            return TaskResult.failure("Failed to prepare module: " + e.getMessage());
        } finally {
            // Cleanup
            connection.execCommand("rm -rf " + remoteTmpDir, becomeContext);
        }
    }

    private String wrapModule(File moduleFile, Map<String, Object> args) throws IOException {
        String moduleCode = Files.readString(moduleFile.toPath(), StandardCharsets.UTF_8);
        String jsonArgs = objectMapper.writeValueAsString(args);

        String base64ModuleCode = Base64.getEncoder().encodeToString(moduleCode.getBytes(StandardCharsets.UTF_8));
        String base64Args = Base64.getEncoder().encodeToString(jsonArgs.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        sb.append("import json, sys, os, base64\n");
        sb.append("complex_args = json.loads(base64.b64decode('").append(base64Args).append("').decode('utf-8'))\n");
        sb.append("def mocked_load_params(*args, **kwargs): return (complex_args, 'main')\n");
        sb.append("try:\n");
        sb.append("    import ansible.module_utils.basic\n");
        sb.append("    ansible.module_utils.basic._load_params = mocked_load_params\n");
        sb.append("    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params\n");
        sb.append("except Exception: pass\n");
        sb.append("module_code = base64.b64decode('").append(base64ModuleCode).append("').decode('utf-8')\n");
        sb.append("if __name__ == '__main__':\n");
        sb.append("    import __main__\n");
        sb.append("    __main__._module_fqn = 'ansible.builtin.").append(moduleName).append("'\n");
        sb.append("    __main__.complex_args = complex_args\n");
        sb.append("    exec(compile(module_code, '").append(moduleName).append(".py', 'exec'), globals())\n");

        return sb.toString();
    }

    private Optional<File> findModuleFile() {
        List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
        for (String path : sitePackages) {
            File modulesDir = new File(path, "ansible/modules");
            if (modulesDir.exists() && modulesDir.isDirectory()) {
                File moduleFile = new File(modulesDir, moduleName + ".py");
                if (moduleFile.exists()) {
                    return Optional.of(moduleFile);
                }
            }
        }
        return Optional.empty();
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
