package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.example.ansible.util.PythonEnv;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.Map;
import java.util.List;
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
        try {
            // Setup sys.path with site-packages
            List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
            Value sys = context.getBindings("python").getMember("sys");
            if (sys == null) {
                context.eval("python", "import sys");
                sys = context.getBindings("python").getMember("sys");
            }
            Value path = sys.getMember("path");
            for (String p : sitePackages) {
                boolean exists = false;
                for (long i = 0; i < path.getArraySize(); i++) {
                    if (p.equals(path.getArrayElement(i).asString())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    path.invokeMember("append", p);
                }
            }

            // Bind values to the Python context
            context.getBindings("python").putMember("complex_args", args);
            context.getBindings("python").putMember("module_name", moduleName);

            String wrapperScript;
            if (scriptContent != null) {
                // Legacy/Mock mode
                context.getBindings("python").putMember("module_code", scriptContent);
                wrapperScript =
                    "import json\n" +
                    "import sys\n" +
                    "from io import StringIO\n" +
                    "\n" +
                    "def run_module():\n" +
                    "    old_stdout = sys.stdout\n" +
                    "    sys.stdout = mystdout = StringIO()\n" +
                    "    try:\n" +
                    "        module_globals = {'complex_args': complex_args, 'ansible_module_results': {}}\n" +
                    "        exec(module_code, module_globals)\n" +
                    "        return mystdout.getvalue()\n" +
                    "    finally:\n" +
                    "        sys.stdout = old_stdout\n" +
                    "\n" +
                    "result = run_module()";
            } else {
                // Actual module mode
                wrapperScript =
                    "import json\n" +
                    "import sys\n" +
                    "import os\n" +
                    "import types\n" +
                    "try:\n" +
                    "    # Recursive mock to handle deep imports of native modules\n" +
                    "    class RecursiveMock(types.ModuleType):\n" +
                    "        def __getattr__(self, name):\n" +
                    "            return RecursiveMock(name)\n" +
                    "        def __call__(self, *args, **kwargs):\n" +
                    "            return RecursiveMock('called')\n" +
                    "    \n" +
                    "    # Aggressively mock native/problematic modules before any imports\n" +
                    "    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux', 'grp', 'pwd']:\n" +
                    "        if mname not in sys.modules:\n" +
                    "            sys.modules[mname] = RecursiveMock(mname)\n" +
                    "\n" +
                    "    from ansible.plugins.loader import module_loader\n" +
                    "    import ansible.module_utils.basic\n" +
                    "    import ansible.module_utils.distro\n" +
                    "    import ansible.module_utils.common.process\n" +
                    "    \n" +
                    "    # Monkeypatch to avoid system interaction\n" +
                    "    ansible.module_utils.distro.id = lambda: 'debian'\n" +
                    "    ansible.module_utils.distro.version = lambda: '12'\n" +
                    "    ansible.module_utils.common.process.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None\n" +
                    "    \n" +
                    "    # Monkeypatch globally before instantiation\n" +
                    "    ansible.module_utils.basic._load_params = lambda: (complex_args, 'main')\n" +
                    "    ansible.module_utils.basic.AnsibleModule._load_params = lambda self: complex_args\n" +
                    "    ansible.module_utils.basic.AnsibleModule._check_locale = lambda self: None\n" +
                    "    ansible.module_utils.basic.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')\n" +
                    "    ansible.module_utils.basic.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None\n" +
                    "    ansible.module_utils.basic.AnsibleModule._record_module_result = lambda self, o: print(json.dumps(o))\n" +
                    "\n" +
                    "    def run_module():\n" +
                    "        path = module_loader.find_plugin(module_name)\n" +
                    "        if not path:\n" +
                    "            return json.dumps({'failed': True, 'msg': f'Module {module_name} not found'})\n" +
                    "        # Capture stdout\n" +
                    "        from io import StringIO\n" +
                    "        old_stdout = sys.stdout\n" +
                    "        sys.stdout = mystdout = StringIO()\n" +
                    "        try:\n" +
                    "            with open(path, 'rb') as f:\n" +
                    "                code = compile(f.read(), path, 'exec')\n" +
                    "            try:\n" +
                    "                exec(code, {'__name__': '__main__', '__file__': path})\n" +
                    "            except SystemExit:\n" +
                    "                pass\n" +
                    "            except Exception as e:\n" +
                    "                import traceback\n" +
                    "                return json.dumps({'failed': True, 'msg': f'Execution error: {str(e)}', 'traceback': traceback.format_exc()})\n" +
                    "            return mystdout.getvalue()\n" +
                    "        finally:\n" +
                    "            sys.stdout = old_stdout\n" +
                    "    result = run_module()\n" +
                    "except ImportError as e:\n" +
                    "    result = json.dumps({'failed': True, 'msg': f'Import error: {str(e)}'})\n";
            }

            context.eval("python", wrapperScript);
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
            
        } catch (Exception e) {
            return TaskResult.failure("GraalPy execution failed: " + e.getMessage());
        }
    }
}
