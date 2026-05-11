package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that returns the base name of a path.
 * Usage: {{ path | basename }}
 */
public class BasenameFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();

        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);

        if (lastSeparator == -1) {
            return path;
        }
        return path.substring(lastSeparator + 1);
    }

    @Override
    public String getName() {
        return "basename";
    }
}
