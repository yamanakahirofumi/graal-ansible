package org.example.ansible.inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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

        for (String source : sources) {
            File sourceFile = new File(source);
            if (sourceFile.isDirectory()) {
                List<File> filesToProcess = expandSource(sourceFile);
                for (File file : filesToProcess) {
                    loadSingleFile(file, inventory, false);
                }
            } else {
                loadSingleFile(sourceFile, inventory, true);
            }
        }

        return inventory;
    }

    private void loadSingleFile(File file, Inventory inventory, boolean failIfUnsupported) {
        String path = file.getPath();
        boolean handled = false;
        for (InventoryProvider provider : providers) {
            if (provider.supports(path)) {
                provider.load(path, inventory);
                handled = true;
                break;
            }
        }
        if (!handled && failIfUnsupported) {
            throw new RuntimeException("No inventory provider found for source: " + path);
        }
        // If not handled and not failIfUnsupported, we silently ignore (typical Ansible behavior for directories)
    }

    private List<File> expandSource(File source) {
        if (!source.exists()) {
            throw new RuntimeException("Inventory source does not exist: " + source.getPath());
        }

        if (source.isFile()) {
            return List.of(source);
        }

        if (source.isDirectory()) {
            List<File> result = new ArrayList<>();
            File[] files = source.listFiles();
            if (files != null) {
                Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                for (File file : files) {
                    if (shouldIgnore(file)) {
                        continue;
                    }
                    result.addAll(expandSource(file));
                }
            }
            return result;
        }

        return List.of();
    }

    private boolean shouldIgnore(File file) {
        String name = file.getName();
        if (name.startsWith(".")) {
            return true;
        }

        if (file.isDirectory()) {
            return name.equals("vars") || name.equals("group_vars") || name.equals("host_vars");
        }

        List<String> ignoreExtensions = List.of("~", ".bak", ".old", ".orig", ".retry", ".rpmnew", ".rpmsave", ".tmp");
        for (String ext : ignoreExtensions) {
            if (name.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }
}
