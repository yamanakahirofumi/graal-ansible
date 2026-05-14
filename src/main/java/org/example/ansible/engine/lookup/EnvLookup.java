package org.example.ansible.engine.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * env lookup plugin: retrieves environment variables.
 */
public class EnvLookup implements Lookup {
    @Override
    public List<Object> execute(List<Object> terms, Map<String, Object> variables) {
        List<Object> results = new ArrayList<>();
        for (Object termObj : terms) {
            String term = termObj != null ? termObj.toString() : "";
            String val = System.getenv(term);
            if (val != null) {
                results.add(val);
            } else {
                results.add("");
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "env";
    }
}
