package org.example.ansible.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility to detect Python environment paths.
 */
public class PythonEnv {

    /**
     * Detects site-package paths from environment variables or system properties.
     * @return A list of paths to site-packages.
     */
    public static List<String> getCollectionPaths(String cliPath) {
        List<String> paths = new ArrayList<>();

        // 1. CLI Option
        if (cliPath != null && !cliPath.isEmpty()) {
            paths.addAll(Arrays.asList(cliPath.split(File.pathSeparator)));
        }

        // 2. Environment Variable
        String envPaths = System.getenv("ANSIBLE_COLLECTIONS_PATH");
        if (envPaths != null && !envPaths.isEmpty()) {
            for (String p : envPaths.split(File.pathSeparator)) {
                if (!paths.contains(p)) {
                    paths.add(p);
                }
            }
        }

        // 3. Default paths
        List<String> defaultPaths = new ArrayList<>();
        defaultPaths.add("./collections");
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            defaultPaths.add(userHome + "/.ansible/collections");
        }
        defaultPaths.add("/usr/share/ansible/collections");

        for (String dp : defaultPaths) {
            File f = new File(dp);
            String absPath = f.getAbsolutePath();
            if (!paths.contains(absPath)) {
                paths.add(absPath);
            }
        }

        return paths;
    }

    public static List<String> getSitePackagesFromEnv() {
        List<String> paths = new ArrayList<>();

        // 1. Check environment variable ANSIBLE_SITE_PACKAGES
        String envPaths = System.getenv("ANSIBLE_SITE_PACKAGES");
        if (envPaths != null && !envPaths.isEmpty()) {
            paths.addAll(Arrays.asList(envPaths.split(File.pathSeparator)));
        }

        // 2. Check system property
        String propPaths = System.getProperty("ansible.site.packages");
        if (propPaths != null && !propPaths.isEmpty()) {
            paths.addAll(Arrays.asList(propPaths.split(File.pathSeparator)));
        }

        // 3. Add default target/python-packages if it exists
        File defaultDir = new File("target/python-packages");
        if (defaultDir.exists() && defaultDir.isDirectory()) {
            paths.add(defaultDir.getAbsolutePath());
        }

        return paths;
    }
}
