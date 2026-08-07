package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * Filter that converts an object to a pretty-printed YAML string (to_nice_yaml).
 * It supports custom indent and width parameters.
 */
public class ToNiceYamlFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (var == null) {
            return "null\n";
        }

        int indent = 4;
        int width = 80;

        if (kwargs != null) {
            if (kwargs.containsKey("indent") && kwargs.get("indent") != null) {
                try {
                    indent = Integer.parseInt(String.valueOf(kwargs.get("indent")));
                } catch (NumberFormatException ignored) {}
            }
            if (kwargs.containsKey("width") && kwargs.get("width") != null) {
                try {
                    width = Integer.parseInt(String.valueOf(kwargs.get("width")));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object argObj = args[i];
                if (argObj == null) continue;
                String argStr = argObj.toString().trim();
                if (argStr.contains("=")) {
                    String[] parts = argStr.split("=", 2);
                    String key = parts[0].trim();
                    String val = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("indent".equals(key)) {
                        try {
                            indent = Integer.parseInt(val);
                        } catch (NumberFormatException ignored) {}
                    } else if ("width".equals(key)) {
                        try {
                            width = Integer.parseInt(val);
                        } catch (NumberFormatException ignored) {}
                    }
                } else {
                    if (i == 0) {
                        if (argObj instanceof Number num) {
                            indent = num.intValue();
                        } else {
                            try {
                                indent = Integer.parseInt(argStr);
                            } catch (NumberFormatException ignored) {}
                        }
                    } else if (i == 1) {
                        if (argObj instanceof Number num) {
                            width = num.intValue();
                        } else {
                            try {
                                width = Integer.parseInt(argStr);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }

        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setIndent(indent);
            options.setWidth(width);
            Yaml yaml = new Yaml(options);
            return yaml.dump(var);
        } catch (Exception e) {
            return var.toString();
        }
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, args, Map.of());
    }

    @Override
    public String getName() {
        return "to_nice_yaml";
    }
}
