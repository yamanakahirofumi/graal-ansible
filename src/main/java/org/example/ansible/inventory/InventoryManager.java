package org.example.ansible.inventory;

import java.util.ArrayList;
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
            loadSourceRecursive(source, inventory, true);
        }

        return inventory;
    }

    private void loadSourceRecursive(String source, Inventory inventory, boolean isExplicit) {
        java.io.File file = new java.io.File(source);
        if (!file.exists()) {
            throw new RuntimeException("Inventory source not found: " + source);
        }

        if (file.isDirectory()) {
            String name = file.getName();
            if (name.equals("vars") || name.equals("group_vars") || name.equals("host_vars")) {
                return;
            }

            java.io.File[] children = file.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children);
                for (java.io.File child : children) {
                    loadSourceRecursive(child.getAbsolutePath(), inventory, false);
                }
            }
        } else {
            if (!isExplicit && shouldIgnoreFile(file)) {
                return;
            }

            boolean handled = false;
            for (InventoryProvider provider : providers) {
                if (provider.supports(source)) {
                    provider.load(source, inventory);
                    handled = true;
                    break;
                }
            }
            if (!handled && isExplicit) {
                throw new RuntimeException("No inventory provider found for source: " + source);
            }
        }
    }

    private boolean shouldIgnoreFile(java.io.File file) {
        String name = file.getName();
        if (name.startsWith(".")) {
            return true;
        }
        if (name.endsWith("~") ||
            name.endsWith(".bak") ||
            name.endsWith(".old") ||
            name.endsWith(".orig") ||
            name.endsWith(".retry") ||
            name.endsWith(".rpmnew") ||
            name.endsWith(".rpmsave") ||
            name.endsWith(".tmp")) {
            return true;
        }
        return false;
    }
}
