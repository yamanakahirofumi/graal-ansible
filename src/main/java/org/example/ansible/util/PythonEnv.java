package org.example.ansible.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility to detect Python environment paths.
 */
public class PythonEnv {
    private static String executable;
    private static List<String> sitePackages;

    public static synchronized String getExecutable() {
        if (executable != null) return executable;

        executable = System.getenv("GRAALPY_EXECUTABLE");
        if (executable == null) executable = System.getProperty("graalpy.executable");

        if (executable == null) {
            executable = findCommand("graalpy");
        }
        if (executable == null) {
            executable = findCommand("python3");
        }
        if (executable == null) {
            executable = findCommand("python");
        }

        if (executable == null) {
            // Last resort defaults
            String[] possiblePaths = {
                "/usr/bin/python3",
                "/usr/local/bin/python3"
            };
            for (String p : possiblePaths) {
                if (Files.exists(Paths.get(p))) {
                    executable = p;
                    break;
                }
            }
        }
        return executable;
    }

    public static synchronized List<String> getSitePackages() {
        if (sitePackages != null) return sitePackages;

        String env = System.getenv("ANSIBLE_SITE_PACKAGES");
        if (env == null) env = System.getProperty("ansible.site.packages");

        if (env != null) {
            sitePackages = Arrays.asList(env.split(","));
        } else {
            String output = queryPython("import site; paths = site.getsitepackages(); user_site = site.getusersitepackages(); " +
                                       "if user_site not in paths: paths.append(user_site); " +
                                       "print(','.join(paths))");
            if (output != null && !output.isEmpty()) {
                sitePackages = Arrays.asList(output.split(","));
            } else {
                sitePackages = new ArrayList<>();
            }
        }

        return sitePackages;
    }

    public static Path getModulesPath() {
        String path = queryPython("import ansible; import os; print(os.path.join(ansible.__path__[0], 'modules'))");
        if (path != null && !path.isEmpty()) {
            return Paths.get(path);
        }
        List<String> packages = getSitePackages();
        String base = packages.isEmpty() ? "/usr/lib/python3/dist-packages" : packages.get(0);
        return Paths.get(base, "ansible", "modules");
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
            if (exe == null) return null;
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
