package org.example.ansible.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to detect Python environment paths.
 */
public class PythonEnv {
    private static String executable;
    private static String sitePackages;

    public static synchronized String getExecutable() {
        if (executable != null) return executable;

        executable = System.getenv("GRAALPY_EXECUTABLE");
        if (executable == null) executable = System.getProperty("graalpy.executable");

        if (executable == null) {
            executable = findCommand("python3");
        }
        if (executable == null) {
            executable = findCommand("graalpy");
        }
        if (executable == null) {
            // Last resort default for the sandbox
            executable = "/home/jules/.pyenv/versions/3.12.12/bin/python3";
        }
        return executable;
    }

    public static synchronized String getSitePackages() {
        if (sitePackages != null) return sitePackages;

        sitePackages = System.getenv("ANSIBLE_SITE_PACKAGES");
        if (sitePackages == null) sitePackages = System.getProperty("ansible.site.packages");

        if (sitePackages == null) {
            sitePackages = queryPython("import site; print(site.getsitepackages()[0] if site.getsitepackages() else '')");
        }

        if (sitePackages == null || sitePackages.isEmpty()) {
            // Fallback for current environment
            sitePackages = "/home/jules/.pyenv/versions/3.12.12/lib/python3.12/site-packages";
        }
        return sitePackages;
    }

    public static Path getModulesPath() {
        String path = queryPython("import ansible; import os; print(os.path.join(ansible.__path__[0], 'modules'))");
        if (path != null && !path.isEmpty()) {
            return Paths.get(path);
        }
        return Paths.get(getSitePackages(), "ansible", "modules");
    }

    private static String findCommand(String command) {
        try {
            Process p = new ProcessBuilder("which", command).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static String queryPython(String script) {
        try {
            String exe = getExecutable();
            Process p = new ProcessBuilder(exe, "-c", script).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}
