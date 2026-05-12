package org.example.ansible.engine.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lookup('env', ...)
 */
public class EnvLookup implements Lookup {
    @Override
    public List<Object> run(List<String> terms, Map<String, Object> variables, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        for (String term : terms) {
            String value = System.getenv(term);
            results.add(value != null ? value : "");
        }
        return results;
    }

    @Override
    public String getName() {
        return "env";
    }
}
