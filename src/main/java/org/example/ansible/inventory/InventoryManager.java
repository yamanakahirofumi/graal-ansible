package org.example.ansible.inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
                List<File> files = new ArrayList<>();
                collectFiles(file, files);

                // Sort by base name, case-insensitive
                Collections.sort(files, Comparator.comparing(f -> f.getName().toLowerCase()));

                for (File f : files) {
                    loadSingleSource(f.getAbsolutePath(), inventory, true);
                }
            } else {
                loadSingleSource(source, inventory, false);
            }
        }

        return inventory;
    }

    private void loadSingleSource(String source, Inventory inventory, boolean isFromDirectory) {
        boolean handled = false;
        for (InventoryProvider provider : providers) {
            if (provider.supports(source)) {
                provider.load(source, inventory);
                handled = true;
                break;
            }
        }
        if (!handled) {
            if (isFromDirectory) {
                LOGGER.warning("Skipping unsupported inventory file discovered in directory: " + source);
            } else {
                throw new RuntimeException("No inventory provider found for source: " + source);
            }
        }
    }

    private void collectFiles(File directory, List<File> collectedFiles) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();

            // Ignore hidden files
            if (name.startsWith(".")) continue;

            if (file.isDirectory()) {
                // Ignore specific directories
                if (IGNORED_DIRECTORIES.contains(name)) continue;
                collectFiles(file, collectedFiles);
            } else {
                // Ignore backup/temporary files
                boolean ignored = false;
                for (String ext : IGNORED_EXTENSIONS) {
                    if (name.endsWith(ext)) {
                        ignored = true;
                        break;
                    }
                }
                if (ignored) continue;

                collectedFiles.add(file);
            }
        }
    }
}
