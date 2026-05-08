package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that quotes a string for shell use.
 */
public class QuoteFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return "''";
        String s = var.toString();
        // Basic implementation of shell quoting
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public String getName() {
        return "quote";
    }
}
