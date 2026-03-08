package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.module.Module;
import org.example.ansible.util.PythonEnv;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;

import java.util.Map;
import java.util.List;
import java.io.File;
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
            context.getBindings("python").putMember("complex_args_java", args);
            context.getBindings("python").putMember("module_name", moduleName);

            // Convert Java Map to native Python dict to avoid pickling issues (e.g., 'ForeignDict')
            context.eval("python", "complex_args = dict(complex_args_java) if complex_args_java is not None else {}");

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
                    "    # Aggressively mock native/problematic modules before any imports\n" +
                    "    # Setting to None triggers ImportError, which is better for many libraries\n" +
                    "    for mname in ['cryptography', 'cryptography.hazmat', 'cryptography.hazmat.bindings', '_cffi_backend', 'yaml._yaml', 'selinux']:\n" +
                    "        sys.modules[mname] = None\n" +
                    "\n" +
                    "    # Mock missing system modules as actual modules\n" +
                    "    import collections\n" +
                    "    passwd = collections.namedtuple('passwd', ['pw_name', 'pw_passwd', 'pw_uid', 'pw_gid', 'pw_gecos', 'pw_dir', 'pw_shell'])\n" +
                    "    group = collections.namedtuple('group', ['gr_name', 'gr_passwd', 'gr_gid', 'gr_mem'])\n" +
                    "    if 'grp' not in sys.modules:\n" +
                    "        m = types.ModuleType('grp')\n" +
                    "        m.getgrnam = m.getgrgid = lambda *args, **kwargs: group('root', 'x', 0, [])\n" +
                    "        sys.modules['grp'] = m\n" +
                    "    if 'pwd' not in sys.modules:\n" +
                    "        m = types.ModuleType('pwd')\n" +
                    "        m.getpwnam = m.getpwuid = lambda *args, **kwargs: passwd('root', 'x', 0, 0, 'root', '/root', '/bin/bash')\n" +
                    "        sys.modules['pwd'] = m\n" +
                    "    # Mock os.geteuid/getuid if missing (common in some GraalPy envs)\n" +
                    "    if not hasattr(os, 'geteuid'): os.geteuid = lambda: 0\n" +
                    "    if not hasattr(os, 'getuid'): os.getuid = lambda: 0\n" +
                    "    if not hasattr(os, 'getgid'): os.getgid = lambda: 0\n" +
                    "    if not hasattr(os, 'getegid'): os.getegid = lambda: 0\n" +
                    "    if 'termios' not in sys.modules or sys.modules['termios'] is None:\n" +
                    "        m = types.ModuleType('termios')\n" +
                    "        m.TCSAFLUSH = 1\n" +
                    "        m.tcgetattr = lambda *args, **kwargs: [0,0,0,0, ' ', ' ', []]\n" +
                    "        m.tcsetattr = lambda *args, **kwargs: None\n" +
                    "        sys.modules['termios'] = m\n" +
                    "\n" +
                    "    # Break circular import in ansible.utils.display\n" +
                    "    if 'ansible.utils.display' not in sys.modules:\n" +
                    "        m = types.ModuleType('ansible.utils.display')\n" +
                    "        class Display:\n" +
                    "            def __init__(self, *args, **kwargs): pass\n" +
                    "            def display(self, *args, **kwargs): pass\n" +
                    "            def debug(self, *args, **kwargs): pass\n" +
                    "            def verbose(self, *args, **kwargs): pass\n" +
                    "            def warning(self, *args, **kwargs): pass\n" +
                    "            def error(self, *args, **kwargs): pass\n" +
                    "        m.Display = Display\n" +
                    "        sys.modules['ansible.utils.display'] = m\n" +
                    "\n" +
                    "    from ansible.plugins.loader import module_loader\n" +
                    "    import ansible.module_utils.basic\n" +
                    "    import ansible.module_utils.distro\n" +
                    "    import ansible.module_utils.common.process\n" +
                    "    \n" +
                    "    # Monkeypatch to avoid system interaction\n" +
                    "    ansible.module_utils.distro.id = lambda *args, **kwargs: 'debian'\n" +
                    "    ansible.module_utils.distro.version = lambda *args, **kwargs: '12'\n" +
                    "    ansible.module_utils.common.process.get_bin_path = lambda *args, **kwargs: '/usr/bin/' + args[0] if args else None\n" +
                    "    \n" +
                    "    # Monkeypatch globally before instantiation\n" +
                    "    ansible.module_utils.basic._load_params = lambda *args, **kwargs: (complex_args, 'main')\n" +
                    "    def mocked_load_params(self, *args, **kwargs):\n" +
                    "        self.params = complex_args\n" +
                    "    ansible.module_utils.basic.AnsibleModule._load_params = mocked_load_params\n" +
                    "    ansible.module_utils.basic.AnsibleModule._check_locale = lambda self, *args, **kwargs: None\n" +
                    "    ansible.module_utils.basic.AnsibleModule.run_command = lambda self, *args, **kwargs: (0, '', '')\n" +
                    "    ansible.module_utils.basic.AnsibleModule.get_bin_path = lambda self, *args, **kwargs: '/usr/bin/' + args[0] if args else None\n" +
                    "    def mocked_record_result(self, o):\n" +
                    "        def default_ser(obj):\n" +
                    "            if isinstance(obj, set): return list(obj)\n" +
                    "            return str(obj)\n" +
                    "        print(json.dumps(o, default=default_ser))\n" +
                    "    ansible.module_utils.basic.AnsibleModule._record_module_result = mocked_record_result\n" +
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
                    "                # Some modules (like setup) might use relative imports if they think they are in a package\n" +
                    "                # For ansible-core modules, they are usually in 'ansible.modules'\n" +
                    "                mod_globals = {'__name__': '__main__', '__file__': path}\n" +
                    "                # If module is in ansible.modules, set __package__ correctly\n" +
                    "                if 'ansible/modules/' in path.replace('\\\\', '/'):\n" +
                    "                    mod_globals['__package__'] = 'ansible.modules'\n" +
                    "                exec(code, mod_globals)\n" +
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

        } catch (PolyglotException e) {
            return TaskResult.failure("GraalPy execution failed (PolyglotException): " + e.getMessage());
        } catch (Exception e) {
            return TaskResult.failure("GraalPy execution failed: " + e.getMessage());
        }
    }
}
