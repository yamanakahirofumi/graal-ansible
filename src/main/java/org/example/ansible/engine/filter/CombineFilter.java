package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.HashMap;
import java.util.Map;

/**
 * Filter that combines multiple maps into one.
 */
public class CombineFilter implements Filter {

    @Override
    @SuppressWarnings("unchecked")
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof Map)) {
            return var;
        }

        Map<Object, Object> result = new HashMap<>((Map<Object, Object>) var);

        for (Object arg : args) {
            if (arg instanceof Map) {
                result.putAll((Map<Object, Object>) arg);
            }
        }

        if (kwargs != null) {
            // kwargs usually contain options like 'recursive', but here we can also treat them as a map to merge?
            // Actually Ansible's combine kwargs are for options.
        }

        return result;
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        // Fallback for older Jinjava or different call style
        if (!(var instanceof Map)) {
            return var;
        }
        return new HashMap<>((Map<Object, Object>) var);
    }

    @Override
    public String getName() {
        return "combine";
    }
}
