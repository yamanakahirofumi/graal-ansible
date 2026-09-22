package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter that converts a dictionary into a list of items, each having 'key' and 'value' keys.
 * This is compatible with Ansible's dict2items filter.
 */
public class Dict2ItemsFilter implements Filter {

    @Override
    @SuppressWarnings("unchecked")
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof Map<?, ?> map)) {
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
                String argStr = argObj.toString();
                if (argStr.contains("=")) {
                    String[] parts = argStr.split("=", 2);
                    String k = parts[0].trim();
                    String v = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("key_name".equals(k)) {
                        keyName = v;
                    } else if ("value_name".equals(k)) {
                        valueName = v;
                    }
                } else {
                    if (i == 0) {
                        keyName = argStr.replace("'", "").replace("\"", "");
                    } else if (i == 1) {
                        valueName = argStr.replace("'", "").replace("\"", "");
                    }
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put(keyName, entry.getKey() != null ? entry.getKey() : "null");
            item.put(valueName, entry.getValue());
            result.add(item);
        }

        return result;
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, args, Map.of());
    }

    @Override
    public String getName() {
        return "dict2items";
    }
}
