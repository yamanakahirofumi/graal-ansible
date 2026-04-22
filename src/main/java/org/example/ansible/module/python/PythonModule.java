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
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * PythonModule handles the execution of Ansible Python modules.
 * It coordinates local execution via GraalPy on the Control Node (管理ノード)
 * or remote execution on the Target Node (ターゲットノード).
 */
public class PythonModule implements Module {
    private final String moduleName;
    private final String scriptContent; // Added back for mocking/legacy support
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, Path> dependencyZipCache = new ConcurrentHashMap<>();

    private static final List<String> FORCED_LOCAL_MODULES = List.of(
            "package_facts"
    );

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

        if (connection != null && !(connection instanceof LocalConnection) && !FORCED_LOCAL_MODULES.contains(moduleName)) {
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
            context.getBindings("python").putMember("environment_java", TaskExecutor.getCurrentEnvironment());

            // Bridge is pre-loaded in TaskExecutor constructor

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

            String jsonOutput = parseModuleOutput(output);

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

        Path dependencyZip;
        try {
            dependencyZip = getOrCreateDependencyZip();
        } catch (IOException e) {
            return TaskResult.failure("Failed to create dependency ZIP: " + e.getMessage());
        }

        String remoteTmpDir = "/tmp/ansible." + UUID.randomUUID();
        String remoteZipPath = remoteTmpDir + "/ansible_lib.zip";
        try {
            String wrappedScript = wrapModule(moduleFile, args, "ansible_lib.zip");

            // Create remote temp dir
            // Use an empty BecomeContext to ensure the directory is owned by the SSH user,
            // allowing the subsequent SCP upload to succeed.
            var mkdirRes = connection.execCommand("mkdir -p " + remoteTmpDir, BecomeContext.empty(), null);
            if (mkdirRes.exitCode() != 0) {
                return TaskResult.failure("Failed to create remote temp dir: " + mkdirRes.stderr());
            }

            // Transfer dependency ZIP
            connection.putFile(dependencyZip, remoteZipPath);

            // Transfer module
            String remoteModulePath = remoteTmpDir + "/Ansiballz_" + moduleName + ".py";
            Path localTempFile = Files.createTempFile("ansible-module-", ".py");
            try {
                Files.writeString(localTempFile, wrappedScript, StandardCharsets.UTF_8);
                connection.putFile(localTempFile, remoteModulePath);
            } finally {
                Files.deleteIfExists(localTempFile);
            }

            // Execute remotely
            var execRes = connection.execCommand("python3 " + remoteModulePath, becomeContext, TaskExecutor.getCurrentEnvironment());
            String output = execRes.stdout();

            if (output == null || output.isBlank()) {
                return TaskResult.failure("Module produced no output (exit code " + execRes.exitCode() + "): " + execRes.stderr());
            }

            String jsonOutput = parseModuleOutput(output);

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
            connection.execCommand("rm -rf " + remoteTmpDir, becomeContext, null);
        }
    }

    private String wrapModule(File moduleFile, Map<String, Object> args, String zipFileName) throws IOException {
        String moduleCode = Files.readString(moduleFile.toPath(), StandardCharsets.UTF_8);
        String jsonArgs = objectMapper.writeValueAsString(args);

        String base64ModuleCode = Base64.getEncoder().encodeToString(moduleCode.getBytes(StandardCharsets.UTF_8));
        String base64Args = Base64.getEncoder().encodeToString(jsonArgs.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        sb.append("import json, sys, os, base64, __main__, types\n");
        sb.append("def patched_dumps(obj, **kw):\n");
        sb.append("    def clean(o):\n");
        sb.append("        if isinstance(o, (bytes, bytearray)):\n");
        sb.append("            try: return o.decode('utf-8')\n");
        sb.append("            except: return o.decode('latin-1')\n");
        sb.append("        if isinstance(o, (str, int, float, bool, type(None))): return o\n");
        sb.append("        if 'WrappedValue' in str(type(o)):\n");
        sb.append("            for attr in ['value', '_value']: \n");
        sb.append("                if hasattr(o, attr): return clean(getattr(o, attr))\n");
        sb.append("        if hasattr(o, 'items'): return {str(k): clean(v) for k, v in o.items()}\n");
        sb.append("        if hasattr(o, '__iter__'): return [clean(i) for i in o]\n");
        sb.append("        if isinstance(o, Exception): return {'failed': True, 'msg': str(o)}\n");
        sb.append("        return o\n");
        sb.append("    return _orig_dumps(clean(obj), **kw)\n");
        sb.append("_orig_dumps = json.dumps\n");
        sb.append("json.dumps = patched_dumps\n");
        sb.append("__main__._module_fqn = 'ansible.builtin.").append(moduleName).append("'\n");
        if (zipFileName != null) {
            sb.append("script_dir = os.path.dirname(os.path.abspath(__file__))\n");
            sb.append("sys.path.insert(0, os.path.join(script_dir, '").append(zipFileName).append("'))\n");
        }
        sb.append("complex_args = json.loads(base64.b64decode('").append(base64Args).append("').decode('utf-8'))\n");
        sb.append("try:\n");
        sb.append("    import ansible.module_utils.basic\n");
        sb.append("    ansible.module_utils.basic._load_params = lambda: (complex_args, 'main')\n");
        sb.append("    ansible.module_utils.basic._ANSIBLE_PROFILE = 'modern'\n");
        sb.append("    def mocked_load_params(self): self.params = complex_args\n");
        sb.append("    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params\n");
        sb.append("except Exception: pass\n");
        sb.append("module_code = base64.b64decode('").append(base64ModuleCode).append("').decode('utf-8')\n");
        sb.append("if __name__ == '__main__':\n");
        sb.append("    __main__.complex_args = complex_args\n");
        sb.append("    exec(compile(module_code, 'Ansiballz_").append(moduleName).append(".py', 'exec'), {'__name__': '__main__', '__package__': 'ansible.modules', 'complex_args': complex_args, '__file__': __file__})\n");

        return sb.toString();
    }

    private static synchronized Path getOrCreateDependencyZip() throws IOException {
        List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
        String key = String.join(":", sitePackages);

        if (dependencyZipCache.containsKey(key)) {
            Path cached = dependencyZipCache.get(key);
            if (Files.exists(cached)) {
                return cached;
            }
        }

        Path zipPath = Files.createTempFile("ansible_lib-", ".zip");
        zipPath.toFile().deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            for (String sitePackage : sitePackages) {
                Path ansibleBase = Paths.get(sitePackage, "ansible");
                if (Files.exists(ansibleBase) && Files.isDirectory(ansibleBase)) {
                    // Add __init__.py and release.py
                    addFileToZip(zos, ansibleBase.resolve("__init__.py"), "ansible/__init__.py");
                    addFileToZip(zos, ansibleBase.resolve("release.py"), "ansible/release.py");
                    addFileToZip(zos, ansibleBase.resolve("modules/__init__.py"), "ansible/modules/__init__.py");

                    // Add core directories recursively
                    String[] coreDirs = {"module_utils", "_vendor", "_internal", "compat"};
                    for (String dirName : coreDirs) {
                        Path dirPath = ansibleBase.resolve(dirName);
                        if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    String relativePath = "ansible/" + dirName + "/" + dirPath.relativize(file).toString().replace(File.separatorChar, '/');
                                    addFileToZip(zos, file, relativePath);
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        }
                    }
                    // For now, we only take from the first site-package that has 'ansible'
                    break;
                }
            }
        }

        dependencyZipCache.put(key, zipPath);
        return zipPath;
    }

    private static void addFileToZip(ZipOutputStream zos, Path file, String zipPath) throws IOException {
        if (!Files.exists(file)) return;
        ZipEntry zipEntry = new ZipEntry(zipPath);
        zos.putNextEntry(zipEntry);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private Optional<File> findModuleFile() {
        String baseName = moduleName;
        if (baseName.startsWith("ansible.builtin.")) {
            baseName = baseName.substring("ansible.builtin.".length());
        } else if (baseName.startsWith("ansible.legacy.")) {
            baseName = baseName.substring("ansible.legacy.".length());
        }

        List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
        for (String path : sitePackages) {
            File modulesDir = new File(path, "ansible/modules");
            if (modulesDir.exists() && modulesDir.isDirectory()) {
                File moduleFile = new File(modulesDir, baseName + ".py");
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

    /**
     * Robustly extracts JSON from module output.
     * Prefers the last line that looks like a JSON object,
     * otherwise falls back to the range between the first '{' and last '}'.
     */
    private String parseModuleOutput(String output) {
        if (output == null || output.isBlank()) return "{}";

        String trimmed = output.trim();
        String[] lines = trimmed.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int start = line.indexOf('{');
            int end = line.lastIndexOf('}');
            if (start != -1 && end != -1 && start < end) {
                // Return only the portion within braces to strip noise like 'j'
                return line.substring(start, end + 1);
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && start < end) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
