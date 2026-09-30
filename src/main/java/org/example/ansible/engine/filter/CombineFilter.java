package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import org.example.ansible.util.Truthiness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Filter that combines multiple maps into one.
 * Supports recursive merging and list_merge strategies according to Ansible specification.
 */
public class CombineFilter implements Filter {

    @Override
    @SuppressWarnings("unchecked")
    public Object filter(Object var, JinjavaInterpreter interpreter, Object[] args, Map<String, Object> kwargs) {
        if (!(var instanceof Map)) {
            return var;
        }

        boolean recursive = false;
        String listMerge = "replace";

        // Parse kwargs options
        if (kwargs != null) {
            if (kwargs.containsKey("recursive")) {
                recursive = Truthiness.isTrue(kwargs.get("recursive"));
            }
            if (kwargs.containsKey("list_merge") && kwargs.get("list_merge") != null) {
                listMerge = String.valueOf(kwargs.get("list_merge")).toLowerCase();
            }
        }

        // Parse option strings from args (e.g. "recursive=true", "list_merge=append")
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String argStr && argStr.contains("=")) {
                    String[] parts = argStr.split("=", 2);
                    String k = parts[0].trim();
                    String v = parts[1].trim().replace("'", "").replace("\"", "");
                    if ("recursive".equals(k)) {
                        recursive = Truthiness.isTrue(v);
                    } else if ("list_merge".equals(k)) {
                        listMerge = v.toLowerCase();
                    }
                }
            }
        }

        Map<Object, Object> result = new HashMap<>((Map<Object, Object>) var);

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Map) {
                    mergeMaps(result, (Map<Object, Object>) arg, recursive, listMerge);
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private void mergeMaps(Map<Object, Object> base, Map<Object, Object> override, boolean recursive, String listMerge) {
        for (Map.Entry<Object, Object> entry : override.entrySet()) {
            Object key = entry.getKey();
            Object overrideVal = entry.getValue();
            Object baseVal = base.get(key);

            if (recursive && baseVal instanceof Map && overrideVal instanceof Map) {
                Map<Object, Object> subMap = new HashMap<>((Map<Object, Object>) baseVal);
                mergeMaps(subMap, (Map<Object, Object>) overrideVal, recursive, listMerge);
                base.put(key, subMap);
            } else if (baseVal instanceof List && overrideVal instanceof List) {
                base.put(key, mergeLists((List<Object>) baseVal, (List<Object>) overrideVal, listMerge));
            } else {
                base.put(key, overrideVal);
            }
        }
    }

    private List<Object> mergeLists(List<Object> baseList, List<Object> overrideList, String listMerge) {
        switch (listMerge) {
            case "append": {
                List<Object> res = new ArrayList<>(baseList);
                res.addAll(overrideList);
                return res;
            }
            case "prepend": {
                List<Object> res = new ArrayList<>(overrideList);
                res.addAll(baseList);
                return res;
            }
            case "append_rp": {
                LinkedHashSet<Object> set = new LinkedHashSet<>(baseList);
                set.addAll(overrideList);
                return new ArrayList<>(set);
            }
            case "keep": {
                return new ArrayList<>(baseList);
            }
            case "replace":
            default:
                return overrideList;
        }
    }

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        return filter(var, interpreter, (Object[]) args, Map.of());
    }

    @Override
    public String getName() {
        return "combine";
    }
}
