package org.example.ansible.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ansible.util.PythonEnv;
import org.example.ansible.util.PythonOSMock;
import org.example.ansible.util.OSHandlerFactory;
import org.example.ansible.util.PythonAnsibleModuleMock;
import org.example.ansible.util.YamlUtil;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Provides inventory data by executing Python-based Ansible inventory plugins.
 */
public class PythonInventoryProvider implements InventoryProvider {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> collectionPaths;

    public PythonInventoryProvider() {
        this(List.of());
    }

    public PythonInventoryProvider(List<String> collectionPaths) {
        this.collectionPaths = new ArrayList<>(collectionPaths);
    }

    @Override
    public boolean supports(String source) {
        File file = new File(source);
        if (!file.exists() || !file.isFile()) {
            return false;
        }

        if (source.endsWith(".yml") || source.endsWith(".yaml")) {
            try (InputStream is = new FileInputStream(file)) {
                Yaml yaml = YamlUtil.createYaml();
                Object data = yaml.load(is);
                if (data instanceof Map<?, ?> map) {
                    return map.containsKey("plugin");
                }
            } catch (Exception e) {
                // Not a valid YAML or other error, assume not supported
                return false;
            }
        }
        return false;
    }

    @Override
    public void load(String source, Inventory inventory) {
        String pluginName = getPluginName(source);
        if (pluginName == null) {
            throw new RuntimeException("Could not find 'plugin' key in inventory source: " + source);
        }

        try (Context context = Context.newBuilder("python")
                .allowAllAccess(true)
                .option("python.IsolateNativeModules", "false")
                .build()) {

            Value bindings = context.getBindings("python");
            PythonOSMock pythonOSMock = new PythonOSMock(OSHandlerFactory.getHandler());
            bindings.putMember("os_java", pythonOSMock);
            bindings.putMember("AnsibleModuleJava", new PythonAnsibleModuleMock.Factory(pythonOSMock));

            // Pre-load the bridge
            context.eval(loadResource("ansible_bridge.py"));

            // Set up environment for the launcher
            bindings.putMember("plugin_name", pluginName);
            bindings.putMember("inventory_path", source);
            bindings.putMember("site_packages_java", PythonEnv.getSitePackagesFromEnv());
            bindings.putMember("collection_paths_java", collectionPaths);

            // Load the launcher
            context.eval(loadResource("ansible_inventory_launcher.py"));
            Value resultValue = bindings.getMember("result");

            if (resultValue == null || !resultValue.isString()) {
                throw new RuntimeException("Inventory plugin produced no valid output");
            }

            JsonNode rootNode = objectMapper.readTree(resultValue.asString());
            if (rootNode.has("failed") && rootNode.get("failed").asBoolean()) {
                if (rootNode.has("traceback")) {
                    System.err.println(rootNode.get("traceback").asText());
                }
                throw new RuntimeException("Inventory plugin failed: " + rootNode.get("msg").asText());
            }

            parseJsonInventory(rootNode, inventory);

        } catch (Exception e) {
            throw new RuntimeException("Failed to execute or parse inventory plugin: " + source, e);
        }
    }

    private String getPluginName(String source) {
        try (InputStream is = new FileInputStream(source)) {
            Yaml yaml = YamlUtil.createYaml();
            Object data = yaml.load(is);
            if (data instanceof Map<?, ?> map) {
                Object plugin = map.get("plugin");
                return plugin != null ? plugin.toString() : null;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private Source loadResource(String name) throws java.io.IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) {
                File file = new File("src/main/python", name);
                if (file.exists()) {
                    return Source.newBuilder("python", file).build();
                }
                throw new java.io.IOException("Resource not found: " + name);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("python", content, name).build();
        }
    }

    private void parseJsonInventory(JsonNode rootNode, Inventory inventory) {
        JsonNode hostsNode = rootNode.get("hosts");
        JsonNode groupsNode = rootNode.get("groups");

        // Parse groups and hierarchy first
        if (groupsNode != null && groupsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = groupsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String groupName = entry.getKey();
                JsonNode groupData = entry.getValue();

                Group group = getOrCreateGroup(inventory, groupName);

                // Vars
                if (groupData.has("vars")) {
                    group.variables().putAll(jsonNodeToMap(groupData.get("vars")));
                }

                // Children
                if (groupData.has("children")) {
                    for (JsonNode childNode : groupData.get("children")) {
                        String childName = childNode.asText();
                        Group childGroup = getOrCreateGroup(inventory, childName);
                        if (group.children().stream().noneMatch(g -> g.name().equals(childName))) {
                            group.children().add(childGroup);
                        }
                    }
                }

                // Hosts in this group
                if (groupData.has("hosts")) {
                    for (JsonNode hostNode : groupData.get("hosts")) {
                        String hostName = hostNode.asText();
                        Host host = getOrCreateHost(inventory, hostName);
                        if (group.hosts().stream().noneMatch(h -> h.name().equals(hostName))) {
                            group.hosts().add(host);
                        }
                    }
                }
            }
        }

        // Host variables
        if (hostsNode != null && hostsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = hostsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String hostName = entry.getKey();
                JsonNode hostData = entry.getValue();

                Host host = getOrCreateHost(inventory, hostName);
                if (hostData.has("vars")) {
                    host.variables().putAll(jsonNodeToMap(hostData.get("vars")));
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
        if (node == null || !node.isObject()) return result;
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
