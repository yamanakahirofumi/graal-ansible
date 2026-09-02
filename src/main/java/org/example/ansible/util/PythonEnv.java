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

        // 3. ansible.cfg configuration
        for (String cfgPath : getCfgCollectionPaths()) {
            if (!paths.contains(cfgPath)) {
                paths.add(cfgPath);
            }
        }

        // 4. Default paths
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

    private static List<String> getCfgCollectionPaths() {
        List<String> cfgPaths = new ArrayList<>();
        List<File> candidates = new ArrayList<>();
        candidates.add(new File("ansible.cfg"));
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            candidates.add(new File(userHome, ".ansible.cfg"));
        }
        candidates.add(new File("/etc/ansible/ansible.cfg"));

        for (File cfgFile : candidates) {
            if (cfgFile.exists() && cfgFile.isFile()) {
                List<String> parsed = parseAnsibleCfgCollectionsPath(cfgFile);
                if (!parsed.isEmpty()) {
                    cfgPaths.addAll(parsed);
                    break; // stop at first found config file according to Ansible precedence
                }
            }
        }
        return cfgPaths;
    }

    public static List<String> parseAnsibleCfgCollectionsPath(File cfgFile) {
        List<String> paths = new ArrayList<>();
        if (cfgFile == null || !cfgFile.exists() || !cfgFile.isFile()) {
            return paths;
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cfgFile, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            boolean inDefaultsSection = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    String sectionName = trimmed.substring(1, trimmed.length() - 1).trim();
                    inDefaultsSection = "defaults".equalsIgnoreCase(sectionName);
                    continue;
                }
                if (inDefaultsSection && trimmed.contains("=")) {
                    String[] parts = trimmed.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim();
                    if ("collections_paths".equalsIgnoreCase(key) || "collections_path".equalsIgnoreCase(key)) {
                        for (String p : val.split("[:;]")) {
                            String path = p.trim();
                            if (!path.isEmpty() && !paths.contains(path)) {
                                paths.add(path);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignore errors reading config
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
