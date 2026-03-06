package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PythonModule implements Module {
    private final String moduleName;
    private final String scriptContent;
    private final Path scriptPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PythonModule(String moduleName, String scriptContent) {
        this.moduleName = moduleName;
        this.scriptContent = scriptContent;
        this.scriptPath = null;
    }

    public PythonModule(String moduleName, Path scriptPath) {
        this.moduleName = moduleName;
        this.scriptPath = scriptPath;
        this.scriptContent = null;
    }

    @Override
    public TaskResult execute(final Map<String, Object> args, BecomeContext becomeContext, Context context) {
        boolean ownContext = (context == null);
        if (ownContext) {
            String pythonExecutable = System.getenv("GRAALPY_EXECUTABLE");
            if (pythonExecutable == null) {
                pythonExecutable = System.getProperty("graalpy.executable", "/home/jules/.pyenv/versions/3.12.12/bin/python3");
            }
            String sitePackages = System.getenv("ANSIBLE_SITE_PACKAGES");
            if (sitePackages == null) {
                sitePackages = System.getProperty("ansible.site.packages", "/home/jules/.pyenv/versions/3.12.12/lib/python3.12/site-packages");
            }

            context = Context.newBuilder("python")
                    .allowAllAccess(true)
                    .option("python.Executable", pythonExecutable)
                    .option("python.PosixModuleBackend", "native")
                    .build();
            context.eval("python", "import sys; sys.path.append('" + sitePackages + "')");
        }

        try {
            String effectiveScript = scriptContent;
            if (scriptPath != null) {
                effectiveScript = Files.readString(scriptPath);
            }

            // Bind values to the Python context
            Value pythonBindings = context.getBindings("python");
            pythonBindings.putMember("complex_args", args);
            pythonBindings.putMember("module_code", effectiveScript);
            pythonBindings.putMember("module_name", moduleName);

            // Python wrapper to simulate Ansible's module execution environment
            final String wrapperScript =
                "import json\n" +
                "import sys\n" +
                "import os\n" +
                "from io import StringIO\n" +
                "\n" +
                "def run_module():\n" +
                "    args = complex_args\n" +
                "    \n" +
                "    # Capture stdout\n" +
                "    old_stdout = sys.stdout\n" +
                "    sys.stdout = mystdout = StringIO()\n" +
                "    \n" +
                "    try:\n" +
                "        # Setup globals for the module\n" +
                "        module_globals = {\n" +
                "            '__name__': '__main__',\n" +
                "            'complex_args': args\n" +
                "        }\n" +
                "        \n" +
                "        # Execute the module code\n" +
                "        exec(module_code, module_globals)\n" +
                "        \n" +
                "        return mystdout.getvalue()\n" +
                "    except SystemExit as e:\n" +
                "        return mystdout.getvalue()\n" +
                "    except Exception as e:\n" +
                "        import traceback\n" +
                "        return json.dumps({'failed': True, 'msg': str(e), 'traceback': traceback.format_exc()})\n" +
                "    finally:\n" +
                "        sys.stdout = old_stdout\n" +
                "\n" +
                "result = run_module()";

            context.eval("python", wrapperScript);
            Value pythonResult = context.getBindings("python").getMember("result");

            if (pythonResult == null || !pythonResult.isString()) {
                return TaskResult.failure("Module produced no valid output");
            }
            final String output = pythonResult.asString();

            if (output == null || output.isBlank()) {
                return TaskResult.failure("Module produced no output");
            }

            String jsonOutput = findJson(output);
            if (jsonOutput == null) {
                return TaskResult.failure("Could not find JSON in module output: " + output);
            }

            @SuppressWarnings("unchecked")
            final Map<String, Object> resultMap = objectMapper.readValue(jsonOutput, Map.class);

            final boolean failed = Boolean.TRUE.equals(resultMap.get("failed"));
            if (failed) {
                return TaskResult.failure(resultMap.getOrDefault("msg", "Module failed").toString(), resultMap);
            }

            return TaskResult.success(resultMap);
            
        } catch (Exception e) {
            return TaskResult.failure("GraalPy execution failed: " + e.getMessage());
        } finally {
            if (ownContext) {
                context.close();
            }
        }
    }

    private String findJson(String output) {
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start != -1 && end != -1 && start < end) {
            return output.substring(start, end + 1);
        }
        return null;
    }
}
