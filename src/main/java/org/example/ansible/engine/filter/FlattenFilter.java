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

        List<Object> result = new ArrayList<>();
        flatten((Iterable<?>) var, result);
        return result;
    }

    private void flatten(Iterable<?> iterable, List<Object> result) {
        for (Object item : iterable) {
            if (item instanceof Iterable && !(item instanceof String)) {
                flatten((Iterable<?>) item, result);
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
