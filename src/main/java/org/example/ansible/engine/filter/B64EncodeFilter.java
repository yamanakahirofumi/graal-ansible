package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Filter that encodes a string to Base64.
 */
public class B64EncodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String s = var.toString();
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getName() {
        return "b64encode";
    }
}
