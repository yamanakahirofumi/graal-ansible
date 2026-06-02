package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * vars lookup plugin: returns the value of the specified variables.
 */
public class VarsLookup implements Lookup {
    @Override
    public List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs) {
        List<Object> results = new ArrayList<>();
        for (Object term : terms) {
            String varName = term != null ? term.toString() : "";
            Object value = interpreter.getContext().get(varName);
            if (value != null || interpreter.getContext().containsKey(varName)) {
                results.add(value);
            } else {
                throw new RuntimeException("vars lookup failed: variable '" + varName + "' not found");
            }
        }
        return results;
    }

    @Override
    public String getName() {
        return "vars";
    }
}
