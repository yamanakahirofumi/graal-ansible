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
