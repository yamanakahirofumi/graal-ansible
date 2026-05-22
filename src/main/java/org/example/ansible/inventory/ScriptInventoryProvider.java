package org.example.ansible.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ansible.util.OSHandler;
import org.example.ansible.util.OSHandlerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Provides inventory data by executing external scripts and parsing their JSON output.
 */
public class ScriptInventoryProvider implements InventoryProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean supports(String source) {
        File file = new File(source);
        if (!file.exists() || !file.isFile()) {
            return false;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            // On Windows, we consider .py, .bat, .ps1, .exe as potential scripts
            return source.endsWith(".py") || source.endsWith(".bat") || source.endsWith(".ps1") || source.endsWith(".exe");
        }

        return file.canExecute();
    }

    @Override
    public void load(String source, Inventory inventory) {
        try {
            OSHandler osHandler = OSHandlerFactory.getHandler();
            List<String> command = new ArrayList<>();
            if ("Windows".equals(osHandler.getOSFamily()) && source.endsWith(".py")) {
                command.add("python");
            }
            command.add(source);
            command.add("--list");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Inventory script failed with exit code " + exitCode + ": " + source);
            }

            JsonNode rootNode = objectMapper.readTree(output.toString());
            parseJsonInventory(rootNode, inventory);

        } catch (Exception e) {
            throw new RuntimeException("Failed to execute or parse inventory script: " + source, e);
        }
    }

    private void parseJsonInventory(JsonNode rootNode, Inventory inventory) {
        Map<String, Map<String, Object>> allHostVars = new HashMap<>();

        // Parse _meta if present
        if (rootNode.has("_meta") && rootNode.get("_meta").has("hostvars")) {
            JsonNode hostVarsNode = rootNode.get("_meta").get("hostvars");
            Iterator<Map.Entry<String, JsonNode>> fields = hostVarsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                allHostVars.put(entry.getKey(), jsonNodeToMap(entry.getValue()));
            }
        }

        // Parse groups
        Iterator<Map.Entry<String, JsonNode>> groupFields = rootNode.fields();
        while (groupFields.hasNext()) {
            Map.Entry<String, JsonNode> groupEntry = groupFields.next();
            String groupName = groupEntry.getKey();
            if ("_meta".equals(groupName)) continue;

            JsonNode groupNode = groupEntry.getValue();
            Group group = getOrCreateGroup(inventory, groupName);

            // Parse vars
            if (groupNode.has("vars")) {
                group.variables().putAll(jsonNodeToMap(groupNode.get("vars")));
            }

            // Parse hosts
            if (groupNode.has("hosts")) {
                JsonNode hostsNode = groupNode.get("hosts");
                if (hostsNode.isArray()) {
                    for (JsonNode hostNode : hostsNode) {
                        String hostName = hostNode.asText();
                        Host host = getOrCreateHost(inventory, hostName);
                        // Add to group if not already there
                        if (group.hosts().stream().noneMatch(h -> h.name().equals(hostName))) {
                            group.hosts().add(host);
                        }
                        // Merge hostvars from _meta
                        if (allHostVars.containsKey(hostName)) {
                            host.variables().putAll(allHostVars.get(hostName));
                        }
                    }
                }
            }

            // Parse children
            if (groupNode.has("children")) {
                JsonNode childrenNode = groupNode.get("children");
                if (childrenNode.isArray()) {
                    for (JsonNode childNode : childrenNode) {
                        String childName = childNode.asText();
                        Group childGroup = getOrCreateGroup(inventory, childName);
                        if (group.children().stream().noneMatch(g -> g.name().equals(childName))) {
                            group.children().add(childGroup);
                        }
                    }
                }
            }
        }
    }

    private Group getOrCreateGroup(Inventory inventory, String name) {
        if ("all".equals(name)) return inventory.all();
        return inventory.getGroup(name).orElseGet(() -> {
            Group newGroup = new Group(name);
            inventory.all().children().add(newGroup);
            return newGroup;
        });
    }

    private Host getOrCreateHost(Inventory inventory, String name) {
        return inventory.getHost(name).orElseGet(() -> {
            Host newHost = new Host(name);
            inventory.all().hosts().add(newHost);
            return newHost;
        });
    }

    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> result = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), jsonNodeValueToObject(entry.getValue()));
        }
        return result;
    }

    private Object jsonNodeValueToObject(JsonNode node) {
        if (node.isObject()) {
            return jsonNodeToMap(node);
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(jsonNodeValueToObject(item));
            }
            return list;
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        } else if (node.isNull()) {
            return null;
        } else {
            return node.asText();
        }
    }
}
