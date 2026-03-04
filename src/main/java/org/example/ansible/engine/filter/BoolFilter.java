package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that converts a value to a boolean.
 */
public class BoolFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return false;
        if (var instanceof Boolean b) return b;
        if (var instanceof String s) {
            if (s.isEmpty() || s.isBlank() || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
                return false;
            }
            return true;
        }
        if (var instanceof Number n) return n.doubleValue() != 0;
        return true;
    }

    @Override
    public String getName() {
        return "bool";
    }
}
