package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Filter for Base64 decoding.
 * Usage: {{ value | b64decode }}
 */
public class B64DecodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(var.toString());
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return var; // Return original if not valid base64
        }
    }

    @Override
    public String getName() {
        return "b64decode";
    }
}
