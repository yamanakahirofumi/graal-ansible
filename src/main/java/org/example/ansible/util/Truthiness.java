package org.example.ansible.util;

import java.util.List;
import java.util.Map;

/**
 * Utility class for Ansible-style truthiness evaluation.
 */
public class Truthiness {

    /**
     * Evaluates if a value is "true" according to Ansible rules.
     *
     * @param value The value to evaluate.
     * @return true if the value is considered true, false otherwise.
     */
    public static boolean isTrue(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if (s.isEmpty() || s.isBlank() || "false".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
                return false;
            }
            return true;
        }
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof List<?> l) return !l.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }
}
