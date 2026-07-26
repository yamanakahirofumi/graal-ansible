package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter that converts a list of dictionaries into a single dictionary.
 * This is compatible with Ansible's items2dict filter.
 */
public class Items2DictFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof List<?> list)) {
            return var;
        }

        String keyName = "key";
        String valueName = "value";

        if (kwargs != null) {
            if (kwargs.containsKey("key_name") && kwargs.get("key_name") != null) {
                keyName = String.valueOf(kwargs.get("key_name"));
            }
            if (kwargs.containsKey("value_name") && kwargs.get("value_name") != null) {
                valueName = String.valueOf(kwargs.get("value_name"));
            }
        }

        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object argObj = args[i];
                if (argObj == null) continue;
                String arg = argObj.toString();
                if (arg.contains("=")) {
                    String[] parts = arg.split("=", 2);
                    String k = parts[0].trim();
                    String v = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("key_name".equals(k)) keyName = v;
                    else if ("value_name".equals(k)) valueName = v;
                } else {
                    if (i == 0) keyName = arg.replace("'", "").replace("\"", "");
                    else if (i == 1) valueName = arg.replace("'", "").replace("\"", "");
                }
            }
        }

        Map<Object, Object> result = new HashMap<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object key = map.get(keyName);
                Object value = map.get(valueName);
                if (key != null) {
                    result.put(key, value);
                }
            }
        }

        return result;
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, args, Map.of());
    }

    @Override
    public String getName() {
        return "items2dict";
    }
}
