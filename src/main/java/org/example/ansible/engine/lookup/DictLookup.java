package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * dict lookup plugin: converts maps into a list of key-value pair maps.
 */
public class DictLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        for (Object term : terms) {
            if (term instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("key", entry.getKey());
                    item.put("value", entry.getValue());
                    results.add(item);
                }
            } else {
                throw new RuntimeException("dict lookup requires a dictionary (Map), but got: " + (term == null ? "null" : term.getClass().getName()));
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "dict";
    }
}
