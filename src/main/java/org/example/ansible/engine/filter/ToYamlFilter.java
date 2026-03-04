package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Filter that converts an object to a YAML string.
 */
public class ToYamlFilter implements Filter {
    private final Yaml yaml;

    public ToYamlFilter() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return yaml.dump(var);
    }

    @Override
    public String getName() {
        return "to_yaml";
    }
}
