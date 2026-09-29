package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import org.example.ansible.util.Truthiness;

import java.util.Map;

/**
 * Filter that returns one of two (or three) values based on a condition.
 * Usage: {{ condition | ternary(true_val, false_val, null_val) }}
 */
public class TernaryFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (args == null || args.length == 0) {
            return var;
        }

        if (var == null && args.length >= 3) {
            return args[2];
        }

        if (Truthiness.isTrue(var)) {
            return args[0];
        } else {
            return args.length >= 2 ? args[1] : var;
        }
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, (Object[]) args, Map.of());
    }

    @Override
    public String getName() {
        return "ternary";
    }
}
