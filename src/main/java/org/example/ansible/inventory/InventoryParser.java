package org.example.ansible.inventory;

import java.io.InputStream;

/**
 * Interface for parsing inventory data.
 */
public interface InventoryParser {
    /**
     * Parses inventory data from an InputStream.
     *
     * @param inputStream The stream containing inventory data.
     * @return The parsed Inventory.
     */
    Inventory parse(InputStream inputStream);
}
