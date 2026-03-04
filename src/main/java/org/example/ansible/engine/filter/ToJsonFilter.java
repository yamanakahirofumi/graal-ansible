package org.example.ansible.engine.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Filter that converts an object to a JSON string.
 */
public class ToJsonFilter implements Filter {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        try {
            return mapper.writeValueAsString(var);
        } catch (JsonProcessingException e) {
            return var.toString();
        }
    }

    @Override
    public String getName() {
        return "to_json";
    }
}
