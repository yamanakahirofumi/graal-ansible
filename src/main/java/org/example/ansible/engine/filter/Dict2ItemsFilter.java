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
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (!(var instanceof Map<?, ?> map)) {
            return var;
        }

        String keyName = "key";
        String valueName = "value";

        // Handle named arguments if passed as a map (Jinjava behavior)
        if (args.length > 0 && args[0] != null) {
            // Very basic support for key_name and value_name from args
            for (String arg : args) {
                if (arg.startsWith("key_name=")) {
                    keyName = arg.substring("key_name=".length()).trim().replace("'", "").replace("\"", "");
                } else if (arg.startsWith("value_name=")) {
                    valueName = arg.substring("value_name=".length()).trim().replace("'", "").replace("\"", "");
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
    public String getName() {
        return "dict2items";
    }
}
