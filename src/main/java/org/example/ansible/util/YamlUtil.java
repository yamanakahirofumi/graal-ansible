package org.example.ansible.util;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.constructor.Construct;

/**
 * Utility for creating configured SnakeYAML instances.
 * Handles unknown tags (like !vault) by treating them as their underlying types.
 */
public class YamlUtil {

    /**
     * Creates a Yaml instance with a custom constructor that handles unknown tags.
     * @return A configured Yaml instance.
     */
    public static Yaml createYaml() {
        return new Yaml(new AnsibleYamlConstructor(new LoaderOptions()));
    }

    private static class AnsibleYamlConstructor extends SafeConstructor {
        public AnsibleYamlConstructor(LoaderOptions options) {
            super(options);
        }

        @Override
        protected Construct getConstructor(Node node) {
            Tag tag = node.getTag();
            if (tag != null && tag.getValue() != null && tag.getValue().startsWith("!")) {
                Construct constructor = yamlConstructors.get(tag);
                if (constructor == null) {
                    // This is an unknown tag. Map it to its base type constructor.
                    switch (node.getNodeId()) {
                        case scalar:
                            return yamlConstructors.get(Tag.STR);
                        case sequence:
                            return yamlConstructors.get(Tag.SEQ);
                        case mapping:
                            return yamlConstructors.get(Tag.MAP);
                        default:
                            return super.getConstructor(node);
                    }
                }
            }
            return super.getConstructor(node);
        }
    }
}
