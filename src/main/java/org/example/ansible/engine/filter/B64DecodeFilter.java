package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Filter that decodes a Base64 encoded string.
 */
public class B64DecodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        try {
            String s = var.toString();
            byte[] decodedBytes = Base64.getDecoder().decode(s);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return var;
        }
    }

    @Override
    public String getName() {
        return "b64decode";
    }
}
