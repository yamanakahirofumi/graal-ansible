package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import org.example.ansible.util.Truthiness;

/**
 * Filter that returns one of two (or three) values based on a condition.
 * Usage: {{ condition | ternary(true_val, false_val, null_val) }}
 */
public class TernaryFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null && args.length >= 3) {
            return args[2];
        }

        if (Truthiness.isTrue(var)) {
            return args.length >= 1 ? args[0] : var;
        } else {
            return args.length >= 2 ? args[1] : var;
        }
    }

    @Override
    public String getName() {
        return "ternary";
    }
}
