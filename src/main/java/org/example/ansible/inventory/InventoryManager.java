package org.example.ansible.inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Manages multiple InventoryProviders and merges inventory data from multiple sources.
 */
public class InventoryManager {

    private final List<InventoryProvider> providers = new ArrayList<>();

    /**
     * Adds a provider to the manager.
     * @param provider The provider to add.
     */
    public void addProvider(InventoryProvider provider) {
        providers.add(provider);
    }

    /**
     * Loads inventory data from one or more sources.
     *
     * @param sources The list of inventory sources (paths or identifiers).
     * @return The merged Inventory object.
     */
    public Inventory loadInventory(List<String> sources) {
        Inventory inventory = new Inventory(new Group("all"));
        List<String> expandedSources = expandSources(sources);

        for (String source : expandedSources) {
            boolean handled = false;
            for (InventoryProvider provider : providers) {
                if (provider.supports(source)) {
                    provider.load(source, inventory);
                    handled = true;
                    break;
                }
            }
            if (!handled) {
                // If the source was explicitly provided by the user and not found/handled, it's an error.
                // But for files discovered in a directory, we might want to be more lenient if some files
                // in the directory are not intended to be inventory files (and are not caught by shouldIgnore).
                // However, Ansible usually expects all files in an inventory directory to be valid inventory sources
                // unless they match exclusion patterns.
                throw new RuntimeException("No inventory provider found for source: " + source);
            }
        }

        return inventory;
    }

    private List<String> expandSources(List<String> sources) {
        List<String> expanded = new ArrayList<>();
        for (String source : sources) {
            File file = new File(source);
            if (!file.exists()) {
                throw new RuntimeException("Inventory source not found: " + source);
            }

            if (file.isDirectory()) {
                List<File> files = new ArrayList<>();
                collectFiles(file, files);
                Collections.sort(files);
                for (File f : files) {
                    expanded.add(f.getAbsolutePath());
                }
            } else {
                expanded.add(source);
            }
        }
        return expanded;
    }

    private void collectFiles(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;

        // Sort to ensure deterministic order during traversal if needed,
        // though we sort the final list as well.
        Arrays.sort(files);
        for (File f : files) {
            if (shouldIgnore(f)) continue;

            if (f.isDirectory()) {
                collectFiles(f, result);
            } else {
                result.add(f);
            }
        }
    }

    private boolean shouldIgnore(File f) {
        String name = f.getName();
        if (name.startsWith(".")) return true;
        if (f.isDirectory()) {
            return name.equals("vars") || name.equals("group_vars") || name.equals("host_vars");
        }
        return name.endsWith("~") ||
               name.endsWith(".bak") ||
               name.endsWith(".old") ||
               name.endsWith(".orig") ||
               name.endsWith(".retry") ||
               name.endsWith(".rpmnew") ||
               name.endsWith(".rpmsave") ||
               name.endsWith(".tmp");
    }
}
