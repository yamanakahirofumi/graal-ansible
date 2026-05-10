package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that fails if the variable is undefined or empty.
 * Usage: {{ value | mandatory }}
 */
public class MandatoryFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null || (var instanceof String && ((String) var).isEmpty())) {
            String msg = "Mandatory variable is undefined or empty";
            if (args.length > 0) {
                msg = args[0];
            }
            throw new RuntimeException(msg);
        }
        return var;
    }

    @Override
    public String getName() {
        return "mandatory";
    }
}
