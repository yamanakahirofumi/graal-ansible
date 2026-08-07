package org.example.ansible.engine.filter;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.Map;

/**
 * Filter that converts an object to a pretty-printed JSON string (to_nice_json).
 * It supports custom indent and sort_keys parameters.
 */
public class ToNiceJsonFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (var == null) {
            return "null";
        }

        int indent = 4;
        boolean sortKeys = true;

        if (kwargs != null) {
            if (kwargs.containsKey("indent") && kwargs.get("indent") != null) {
                try {
                    indent = Integer.parseInt(String.valueOf(kwargs.get("indent")));
                } catch (NumberFormatException ignored) {}
            }
            if (kwargs.containsKey("sort_keys") && kwargs.get("sort_keys") != null) {
                sortKeys = Boolean.parseBoolean(String.valueOf(kwargs.get("sort_keys")));
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
                    } else if ("sort_keys".equals(key)) {
                        sortKeys = Boolean.parseBoolean(val);
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
                        if (argObj instanceof Boolean b) {
                            sortKeys = b;
                        } else {
                            sortKeys = Boolean.parseBoolean(argStr);
                        }
                    }
                }
            }
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            if (sortKeys) {
                mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            }

            DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
            DefaultIndenter indenter = new DefaultIndenter(" ".repeat(Math.max(0, indent)), "\n");
            prettyPrinter.indentObjectsWith(indenter);
            prettyPrinter.indentArraysWith(indenter);

            return mapper.writer(prettyPrinter).writeValueAsString(var);
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
        return "to_nice_json";
    }
}
