package org.example.ansible.inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages multiple InventoryProviders and merges inventory data from multiple sources.
 */
public class InventoryManager {

    private static final Logger LOGGER = Logger.getLogger(InventoryManager.class.getName());
    private final List<InventoryProvider> providers = new ArrayList<>();

    private static final List<String> IGNORED_EXTENSIONS = List.of(
            "~", ".bak", ".old", ".orig", ".retry", ".rpmnew", ".rpmsave", ".tmp"
    );

    private static final List<String> IGNORED_DIRECTORIES = List.of(
            "vars", "group_vars", "host_vars"
    );

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
            File file = new File(source);
            if (file.isDirectory()) {
                List<File> dirFiles = new ArrayList<>();
                collectFiles(file, dirFiles);
                // Ansible sorts discovered files in a directory by filename alphabetically (case-insensitive)
                dirFiles.sort((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
                for (File df : dirFiles) {
                    loadSource(df.getPath(), inventory, false);
                }
            } else {
                // For non-directory sources (files or dynamic identifiers), we use strict mode
                loadSource(source, inventory, true);
            }
        }

        return inventory;
    }

    private void loadSource(String source, Inventory inventory, boolean explicit) {
        boolean handled = false;
        for (InventoryProvider provider : providers) {
            if (provider.supports(source)) {
                provider.load(source, inventory);
                handled = true;
                break;
            }
        }
        if (!handled) {
            if (explicit) {
                throw new RuntimeException("No inventory provider found for source: " + source);
            } else {
                LOGGER.log(Level.WARNING, "Skipping unsupported inventory file: {0}", source);
            }
        }
    }

    private void collectFiles(File directory, List<File> collected) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (shouldIgnore(file)) continue;

            if (file.isDirectory()) {
                collectFiles(file, collected);
            } else {
                collected.add(file);
            }
        }
    }

    private boolean shouldIgnore(File file) {
        String name = file.getName();
        // Ignore hidden files
        if (name.startsWith(".")) return true;

        if (file.isDirectory()) {
            // Ignore specific inventory variable directories
            return IGNORED_DIRECTORIES.contains(name);
        }

        // Ignore backup and temporary files
        for (String ext : IGNORED_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }

        return false;
    }
}
