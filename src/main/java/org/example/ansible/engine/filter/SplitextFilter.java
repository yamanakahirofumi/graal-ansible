package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.io.File;
import java.util.List;

/**
 * Filter that splits a path into name and extension.
 * Usage: {{ path | splitext }}
 */
public class SplitextFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String path = var.toString();
        int lastDot = path.lastIndexOf('.');
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));

        if (lastDot > lastSeparator) {
            return List.of(path.substring(0, lastDot), path.substring(lastDot));
        } else {
            return List.of(path, "");
        }
    }

    @Override
    public String getName() {
        return "splitext";
    }
}
