package org.example.ansible.inventory;

/**
 * Interface for providing inventory data from various sources.
 */
public interface InventoryProvider {
    /**
     * Determines if this provider supports the given source.
     *
     * @param source The source path or identifier.
     * @return true if the provider supports the source, false otherwise.
     */
    boolean supports(String source);

    /**
     * Loads inventory data from the source and updates the provided Inventory object.
     *
     * @param source    The source path or identifier.
     * @param inventory The Inventory object to update.
     */
    void load(String source, Inventory inventory);
}
