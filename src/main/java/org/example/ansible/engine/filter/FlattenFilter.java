package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Filter that flattens a nested list.
 * Usage: {{ list | flatten }} or {{ list | flatten(levels=1) }}
 */
public class FlattenFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof Iterable)) {
            return var;
        }

        int levels = -1; // -1 means flatten all

        if (kwargs != null && kwargs.containsKey("levels") && kwargs.get("levels") != null) {
            Object lvlObj = kwargs.get("levels");
            if (lvlObj instanceof Number n) {
                levels = n.intValue();
            } else {
                try {
                    levels = Integer.parseInt(lvlObj.toString());
                } catch (NumberFormatException ignored) {}
            }
        } else if (args != null && args.length > 0 && args[0] != null) {
            Object arg = args[0];
            if (arg instanceof Number n) {
                levels = n.intValue();
            } else {
                try {
                    levels = Integer.parseInt(arg.toString());
                } catch (NumberFormatException ignored) {}
            }
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
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, (Object[]) args, Map.of());
    }

    @Override
    public String getName() {
        return "flatten";
    }
}
