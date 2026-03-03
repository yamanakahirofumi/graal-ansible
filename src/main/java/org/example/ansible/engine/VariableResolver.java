package org.example.ansible.engine;

import com.hubspot.jinjava.Jinjava;
import org.example.ansible.engine.filter.DefaultFilter;
import org.example.ansible.engine.filter.IpAddrFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Variable resolver using Jinjava for Jinja2 compatibility.
 */
public class VariableResolver {
    private final Jinjava jinjava;

    public VariableResolver() {
        this.jinjava = new Jinjava();
        registerFilters();
    }

    private void registerFilters() {
        jinjava.getGlobalContext().registerFilter(new DefaultFilter());
        jinjava.getGlobalContext().registerFilter(new IpAddrFilter());
    }

    /**
     * Resolves variables in a map of arguments.
     *
     * @param args      The arguments to resolve.
     * @param variables The variable map to use for resolution.
     * @return A new map with resolved values.
     */
    public Map<String, Object> resolve(Map<String, Object> args, Map<String, Object> variables) {
        if (args == null) return null;
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), variables));
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    /**
     * Resolves variables in a single value.
     *
     * @param value     The value to resolve.
     * @param variables The variable map.
     * @return The resolved value.
     */
    @SuppressWarnings("unchecked")
    public Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String str) {
            return resolveString(str, variables);
        } else if (value instanceof Map<?, ?> map) {
            return resolve((Map<String, Object>) map, variables);
        } else if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> resolveValue(item, variables))
                    .collect(Collectors.toList());
        }
        return value;
    }

    private Object resolveString(String input, Map<String, Object> variables) {
        if (input == null) return null;
        if (!input.contains("{{")) {
            return input;
        }
        return jinjava.render(input, variables);
    }
}
