package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.module.Module;
import org.example.ansible.module.python.PythonModule;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;
import org.graalvm.polyglot.Context;

import java.util.Map;
import java.util.HashMap;

/**
 * Executes individual tasks by delegating to modules.
 */
public class TaskExecutor implements AutoCloseable {

    private final Map<String, Module> modules = new HashMap<>();
    private final OSHandler osHandler;
    private Context pythonContext;

    public TaskExecutor() {
        this(OSHandlerFactory.getHandler());
    }

    public TaskExecutor(OSHandler osHandler) {
        this.osHandler = osHandler;
    }

    private synchronized Context getPythonContext() {
        if (pythonContext == null) {
            String pythonExecutable = System.getenv("GRAALPY_EXECUTABLE");
            if (pythonExecutable == null) {
                pythonExecutable = System.getProperty("graalpy.executable", "/home/jules/.pyenv/versions/3.12.12/bin/python3");
            }

            String sitePackages = System.getenv("ANSIBLE_SITE_PACKAGES");
            if (sitePackages == null) {
                sitePackages = System.getProperty("ansible.site.packages", "/home/jules/.pyenv/versions/3.12.12/lib/python3.12/site-packages");
            }

            pythonContext = Context.newBuilder("python")
                    .allowAllAccess(true)
                    .option("python.Executable", pythonExecutable)
                    .option("python.PosixModuleBackend", "native")
                    .build();

            // Initial setup
            final String initScript =
                "import sys\n" +
                "sys.path.append('" + sitePackages + "')\n" +
                "import polyglot\n" +
                "def mock_module(name, attrs):\n" +
                "    import types\n" +
                "    m = types.ModuleType(name)\n" +
                "    for k, v in attrs.items(): setattr(m, k, v)\n" +
                "    sys.modules[name] = m\n" +
                "try:\n" +
                "    import grp\n" +
                "except ImportError:\n" +
                "    mock_module('grp', {'getgrnam': lambda x: ('mock',), 'getgrgid': lambda x: ('mock',), 'getallgr': lambda: []})\n" +
                "try:\n" +
                "    import pwd\n" +
                "except ImportError:\n" +
                "    mock_module('pwd', {'getpwnam': lambda x: ('mock',), 'getpwuid': lambda x: ('mock',), 'getallpwent': lambda: []})\n" +
                "import os\n" +
                "if not hasattr(os, 'geteuid'): os.geteuid = lambda: 1001\n" +
                "if not hasattr(os, 'getuid'): os.getuid = lambda: 1001\n" +
                "if not hasattr(os, 'getgid'): os.getgid = lambda: 1001\n" +
                "if not hasattr(os, 'getegid'): os.getegid = lambda: 1001\n" +
                "import ansible.module_utils.basic\n" +
                "import ansible.module_utils.common.text.converters\n" +
                "import json\n" +
                "def patched_load_params(self):\n" +
                "    # Bypass stdin reading and use complex_args provided by Java\n" +
                "    # Convert ForeignDict to native dict to avoid pickling issues\n" +
                "    self.params = dict(complex_args) if 'complex_args' in globals() else {}\n" +
                "ansible.module_utils.basic.AnsibleModule._load_params = patched_load_params\n";
            pythonContext.eval("python", initScript);
        }
        return pythonContext;
    }

    /**
     * Gets the OS handler used by the executor.
     * @return The OSHandler.
     */
    public OSHandler getOsHandler() {
        return osHandler;
    }

    /**
     * Registers a module for a specific action name.
     *
     * @param action The action name (e.g., "debug", "command").
     * @param module The module implementation.
     */
    public void registerModule(String action, Module module) {
        modules.put(action, module);
    }

    /**
     * Executes the given task.
     *
     * @param task          The task to execute.
     * @param becomeContext The privilege escalation context.
     * @return The execution result.
     */
    public TaskResult execute(Task task, BecomeContext becomeContext) {
        Module module = modules.get(task.action());
        if (module == null) {
            return TaskResult.failure("Module not found: " + task.action());
        }
        try {
            Context context = this.pythonContext;
            if (context == null && module instanceof PythonModule) {
                context = getPythonContext();
            }
            return module.execute(task.args(), becomeContext, context);
        } catch (Exception e) {
            return TaskResult.failure("Execution failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (pythonContext != null) {
            pythonContext.close();
            pythonContext = null;
        }
    }
}
