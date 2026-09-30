package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filter that returns a list of unique elements from a list.
 * Usage: {{ list | unique }} or {{ list | unique(attribute='id') }}
 */
public class UniqueFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof Collection<?> collection)) {
            return var;
        }

        String attribute = null;

        if (kwargs != null && kwargs.containsKey("attribute") && kwargs.get("attribute") != null) {
            attribute = String.valueOf(kwargs.get("attribute"));
        }

        if (attribute == null && args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object argObj = args[i];
                if (argObj == null) continue;
                String argStr = argObj.toString();
                if (argStr.contains("=")) {
                    String[] parts = argStr.split("=", 2);
                    String k = parts[0].trim();
                    String v = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("attribute".equals(k)) attribute = v;
                } else {
                    if (i == 0) attribute = argStr.replace("'", "").replace("\"", "");
                }
            }
        }

        if (attribute == null) {
            // Standard unique elements
            return new ArrayList<>(new LinkedHashSet<>(collection));
        } else {
            // Unique by attribute
            List<Object> result = new ArrayList<>();
            Set<Object> seenAttributes = new HashSet<>();
            for (Object item : collection) {
                if (item instanceof Map<?, ?> map) {
                    Object attrValue = map.get(attribute);
                    if (seenAttributes.add(attrValue)) {
                        result.add(item);
                    }
                } else {
                    // Fallback for non-map items if attribute is specified but not present
                    result.add(item);
                }
            }
            return result;
        }
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, (Object[]) args, Map.of());
    }

    @Override
    public String getName() {
        return "unique";
    }
}
