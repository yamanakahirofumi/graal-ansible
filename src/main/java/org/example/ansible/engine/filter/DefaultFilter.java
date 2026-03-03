package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that provides a default value if the input is undefined or empty.
 */
public class DefaultFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var != null && (!(var instanceof String) || !((String) var).isEmpty())) {
            return var;
        }

        if (args.length > 0) {
            return args[0];
        }

        return var;
    }

    @Override
    public String getName() {
        return "default";
    }
}
