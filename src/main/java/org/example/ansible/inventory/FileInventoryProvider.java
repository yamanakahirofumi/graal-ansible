package org.example.ansible.inventory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Provides inventory data from static files (INI or YAML).
 */
public class FileInventoryProvider implements InventoryProvider {

    @Override
    public boolean supports(String source) {
        File file = new File(source);
        if (!file.exists() || !file.isFile()) {
            return false;
        }

        // On Windows, canExecute() is often true even for non-executable files.
        // We rely more on file extensions for static files if on Windows.
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            return source.endsWith(".ini") || source.endsWith(".yml") || source.endsWith(".yaml") || source.endsWith(".txt");
        }

        return !file.canExecute();
    }

    @Override
    public void load(String source, Inventory inventory) {
        InventoryParser parser;
        if (source.endsWith(".yml") || source.endsWith(".yaml")) {
            parser = new YamlInventoryParser();
        } else {
            parser = new IniInventoryParser();
        }

        try (InputStream is = new FileInputStream(source)) {
            Inventory loaded = parser.parse(is);
            mergeInventory(inventory, loaded);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load inventory file: " + source, e);
        }
    }

    private void mergeInventory(Inventory target, Inventory source) {
        mergeGroup(target.all(), source.all());
    }

    private void mergeGroup(Group target, Group source) {
        // Merge variables
        target.variables().putAll(source.variables());

        // Merge hosts
        for (Host sourceHost : source.hosts()) {
            Host targetHost = findHostInGroup(target, sourceHost.name());
            if (targetHost == null) {
                targetHost = new Host(sourceHost.name());
                target.hosts().add(targetHost);
            }
            targetHost.variables().putAll(sourceHost.variables());
        }

        // Merge children groups
        for (Group sourceChild : source.children()) {
            Group targetChild = findGroupInChildren(target, sourceChild.name());
            if (targetChild == null) {
                targetChild = new Group(sourceChild.name());
                target.children().add(targetChild);
            }
            mergeGroup(targetChild, sourceChild);
        }
    }

    private Host findHostInGroup(Group group, String name) {
        return group.hosts().stream()
                .filter(h -> h.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private Group findGroupInChildren(Group group, String name) {
        return group.children().stream()
                .filter(g -> g.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
