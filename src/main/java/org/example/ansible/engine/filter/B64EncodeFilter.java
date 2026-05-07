package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Filter for Base64 encoding.
 * Usage: {{ value | b64encode }}
 */
public class B64EncodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        byte[] bytes;
        if (var instanceof byte[]) {
            bytes = (byte[]) var;
        } else {
            bytes = var.toString().getBytes(StandardCharsets.UTF_8);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public String getName() {
        return "b64encode";
    }
}
