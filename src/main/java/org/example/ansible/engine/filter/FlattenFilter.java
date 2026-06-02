package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Filter that flattens a nested list.
 * Usage: {{ list | flatten }}
 */
public class FlattenFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (!(var instanceof Iterable)) {
            return var;
        }

        int levels = -1; // -1 means flatten all
        if (args.length > 0) {
            try {
                levels = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        List<Object> result = new ArrayList<>();
        flatten((Iterable<?>) var, result, levels);
        return result;
    }

    private void flatten(Iterable<?> iterable, List<Object> result, int levels) {
        for (Object item : iterable) {
            if (item instanceof Iterable && (levels == -1 || levels > 0)) {
                flatten((Iterable<?>) item, result, levels == -1 ? -1 : levels - 1);
            } else {
                result.add(item);
            }
        }
    }

    @Override
    public String getName() {
        return "flatten";
    }
}
