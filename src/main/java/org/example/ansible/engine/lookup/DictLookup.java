package org.example.ansible.engine.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lookup('dict', ...)
 */
public class DictLookup implements Lookup {
    @Override
    @SuppressWarnings("unchecked")
    public List<Object> run(List<String> terms, Map<String, Object> variables, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        for (String term : terms) {
            Object dictObj = variables.get(term);
            if (dictObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    results.add(Map.of("key", entry.getKey(), "value", entry.getValue()));
                }
            } else if (dictObj == null) {
                // If not in variables, it might be a direct map passed (though usually terms are variable names in dict lookup)
                // Ansible dict lookup usually takes a variable name.
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "dict";
    }
}
