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
 * This is compatible with Ansible's unique filter.
 */
public class UniqueFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (!(var instanceof Collection<?> collection)) {
            return var;
        }

        String attribute = null;
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) continue;
                if (arg.contains("=")) {
                    String[] parts = arg.split("=", 2);
                    String k = parts[0].trim();
                    String v = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("attribute".equals(k)) attribute = v;
                } else {
                    if (i == 0) attribute = arg.replace("'", "").replace("\"", "");
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
    public String getName() {
        return "unique";
    }
}
