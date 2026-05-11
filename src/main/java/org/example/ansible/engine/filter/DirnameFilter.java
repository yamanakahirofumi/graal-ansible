package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that returns the directory name of a path.
 * Usage: {{ path | dirname }}
 */
public class DirnameFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();

        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);

        if (lastSeparator == -1) {
            return ".";
        }
        if (lastSeparator == 0) {
            return path.substring(0, 1);
        }
        return path.substring(0, lastSeparator);
    }

    @Override
    public String getName() {
        return "dirname";
    }
}
