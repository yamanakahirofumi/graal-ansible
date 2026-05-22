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
            boolean handled = false;
            for (InventoryProvider provider : providers) {
                if (provider.supports(source)) {
                    provider.load(source, inventory);
                    handled = true;
                    break;
                }
            }
            if (!handled) {
                throw new RuntimeException("No inventory provider found for source: " + source);
            }
        }

        return inventory;
    }
}
