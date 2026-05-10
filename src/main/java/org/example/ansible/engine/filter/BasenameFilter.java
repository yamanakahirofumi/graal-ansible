package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.io.File;

/**
 * Filter that returns the base name of a path.
 * Usage: {{ path | basename }}
 */
public class BasenameFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();
        return new File(path).getName();
    }

    @Override
    public String getName() {
        return "basename";
    }
}
