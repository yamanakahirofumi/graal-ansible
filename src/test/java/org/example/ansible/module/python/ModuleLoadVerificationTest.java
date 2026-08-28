package org.example.ansible.module.python;

import org.example.ansible.util.PythonEnv;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ModuleLoadVerificationTest {

    private Context context;
    private File modulesDir;

    private static final List<String> BUILTIN_MODULES = List.of(
        "debug", "ping", "copy", "file", "template", "stat", "command", "shell", "setup",
        "lineinfile", "replace", "user", "group", "find", "tempfile", "hostname", "slurp",
        "set_fact", "assert", "fail", "gather_facts", "add_host", "apt", "apt_key",
        "apt_repository", "assemble", "async_status", "async_wrapper", "blockinfile",
        "cron", "deb822_repository", "debconf", "dnf", "dnf5", "dpkg_selections",
        "expect", "fetch", "get_url", "getent", "git", "group_by", "import_playbook",
        "import_role", "import_tasks", "include_role", "include_tasks", "include_vars",
        "iptables", "known_hosts", "meta", "mount_facts", "package", "package_facts",
        "pause", "pip", "raw", "reboot", "rpm_key", "script", "service", "service_facts",
        "set_stats", "subversion", "systemd", "systemd_service", "sysvinit", "unarchive",
        "uri", "validate_argument_spec", "wait_for", "wait_for_connection", "yum_repository"
    );

    @BeforeEach
    void setUp() {
        Context.Builder builder = Context.newBuilder("python")
                .allowAllAccess(true);
        builder.option("python.IsolateNativeModules", "false");
        this.context = builder.build();

        List<String> sitePackages = PythonEnv.getSitePackagesFromEnv();
        for (String path : sitePackages) {
            File dir = new File(path, "ansible/modules");
            if (dir.exists() && dir.isDirectory()) {
                this.modulesDir = dir;
                break;
            }
        }

        if (!sitePackages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("import sys, types\n");
            for (String sp : sitePackages) {
                sb.append("if '").append(sp.replace("\\", "/")).append("' not in sys.path:\n");
                sb.append("    sys.path.insert(0, '").append(sp.replace("\\", "/")).append("')\n");
            }
            sb.append("if 'grp' not in sys.modules:\n");
            sb.append("    grp_mock = types.ModuleType('grp')\n");
            sb.append("    grp_mock.getgrnam = lambda *a, **kw: None\n");
            sb.append("    grp_mock.getgrgid = lambda *a, **kw: None\n");
            sb.append("    grp_mock.getgrall = lambda: []\n");
            sb.append("    sys.modules['grp'] = grp_mock\n");
            context.eval("python", sb.toString());
        }
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void testVerifyAllModulesExistAndCompile() {
        assertNotNull(modulesDir, "Ansible modules directory could not be located in site-packages");

        for (String moduleName : BUILTIN_MODULES) {
            File moduleFile = new File(modulesDir, moduleName + ".py");
            assertTrue(moduleFile.exists(), "Module file " + moduleFile.getAbsolutePath() + " does not exist");

            try {
                context.getBindings("python").putMember("module_file_path", moduleFile.getAbsolutePath());
                context.eval("python", "import py_compile\npy_compile.compile(module_file_path, doraise=True)");
            } catch (PolyglotException e) {
                fail("Failed to compile module " + moduleName + ": " + e.getMessage(), e);
            }
        }
    }

    @Test
    void testVerifyModuleUtilsImportable() {
        try {
            context.eval("python", "import ansible.module_utils.basic\nimport ansible.module_utils.common.text.converters");
        } catch (PolyglotException e) {
            fail("Failed to import ansible.module_utils: " + e.getMessage(), e);
        }
    }
}
