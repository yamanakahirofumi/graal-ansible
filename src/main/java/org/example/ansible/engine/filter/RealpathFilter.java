package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.io.File;

/**
 * Filter that returns the absolute path.
 * Usage: {{ path | realpath }}
 */
public class RealpathFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();
        try {
            return new File(path).getCanonicalPath();
        } catch (java.io.IOException e) {
            return new File(path).getAbsolutePath();
        }
    }

    @Override
    public String getName() {
        return "realpath";
    }
}
