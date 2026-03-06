package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.example.ansible.util.PythonEnv;
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

    public PythonModule(String moduleName) {
        this.moduleName = moduleName;
        this.scriptContent = null;
        this.scriptPath = null;
    }

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
            String pythonExecutable = PythonEnv.getExecutable();
            java.util.List<String> sitePackagesList = PythonEnv.getSitePackages();

            context = Context.newBuilder("python")
                    .allowAllAccess(true)
                    .option("python.Executable", pythonExecutable)
                    .option("python.PosixModuleBackend", "native")
                    .build();

            StringBuilder sb = new StringBuilder();
            sb.append("import sys\n");
            for (String p : sitePackagesList) {
                sb.append("sys.path.append('").append(p).append("')\n");
            }
            context.eval("python", sb.toString());
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
                "import importlib.util\n" +
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
                "        code = module_code\n" +
                "        if code is None:\n" +
                "            # Try to find module by name\n" +
                "            name = module_name\n" +
                "            if name.startswith('ansible.builtin.'): name = name[len('ansible.builtin.'):]\n" +
                "            from ansible.plugins.loader import module_loader\n" +
                "            path = module_loader.find_plugin(name)\n" +
                "            if path:\n" +
                "                with open(path, 'r') as f: code = f.read()\n" +
                "            else:\n" +
                "                return json.dumps({'failed': True, 'msg': f'Module not found: {module_name}'})\n" +
                "        \n" +
                "        # Setup globals for the module\n" +
                "        module_globals = {\n" +
                "            '__name__': '__main__',\n" +
                "            'complex_args': args\n" +
                "        }\n" +
                "        \n" +
                "        # Execute the module code\n" +
                "        exec(code, module_globals)\n" +
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
