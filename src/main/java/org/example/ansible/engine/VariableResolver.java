package org.example.ansible.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple variable resolver for Ansible-like {{ var }} placeholders.
 */
public class VariableResolver {
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

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
        return Map.copyOf(resolved);
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

    private String resolveString(String input, Map<String, Object> variables) {
        Matcher matcher = VAR_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object varValue = variables.get(varName);
            if (varValue != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(varValue.toString()));
            } else {
                // If variable not found, leave it as is or handle accordingly.
                // For now, we leave the placeholder.
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
