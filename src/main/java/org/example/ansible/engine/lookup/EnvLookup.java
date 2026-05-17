package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * env lookup plugin: returns the value of environment variables.
 */
public class EnvLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        Object defaultValue = kwargs.get("default");

        for (Object term : terms) {
            String value = System.getenv(term.toString());
            if (value != null) {
                results.add(value);
            } else if (defaultValue != null) {
                results.add(defaultValue);
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "env";
    }
}
