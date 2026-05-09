package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter for shell-quoting strings.
 * Usage: {{ value | quote }}
 */
public class QuoteFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return "''";
        String s = var.toString();
        if (s.isEmpty()) return "''";

        // Simple shell quoting logic: wrap in single quotes and escape existing single quotes
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public String getName() {
        return "quote";
    }
}
