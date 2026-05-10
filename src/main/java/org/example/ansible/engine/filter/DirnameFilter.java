package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.io.File;

/**
 * Filter that returns the directory name of a path.
 * Usage: {{ path | dirname }}
 */
public class DirnameFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();
        String parent = new File(path).getParent();
        return parent != null ? parent : ".";
    }

    @Override
    public String getName() {
        return "dirname";
    }
}
