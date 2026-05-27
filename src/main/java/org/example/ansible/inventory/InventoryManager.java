package org.example.ansible.inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
            loadSource(source, inventory);
        }

        return inventory;
    }

    private void loadSource(String source, Inventory inventory) {
        File file = new File(source);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                // Sort files to ensure deterministic loading order
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File child : files) {
                    if (child.isFile()) {
                        loadSingleFile(child.getPath(), inventory, false);
                    } else if (child.isDirectory()) {
                        loadSource(child.getPath(), inventory);
                    }
                }
            }
        } else {
            loadSingleFile(source, inventory, true);
        }
    }

    private void loadSingleFile(String source, Inventory inventory, boolean throwOnNotFound) {
        boolean handled = false;
        for (InventoryProvider provider : providers) {
            if (provider.supports(source)) {
                provider.load(source, inventory);
                handled = true;
                break;
            }
        }
        if (!handled && throwOnNotFound) {
            throw new RuntimeException("No inventory provider found for source: " + source);
        }
    }
}
