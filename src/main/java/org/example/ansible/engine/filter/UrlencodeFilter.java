package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filter that URL-encodes a string or a map.
 * Compatible with Ansible's urlencode filter.
 */
public class UrlencodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) {
            return "";
        }

        if (var instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> encode(String.valueOf(entry.getKey())) + "=" + encode(String.valueOf(entry.getValue())))
                    .collect(Collectors.joining("&"));
        }

        return encode(String.valueOf(var));
    }

    private String encode(String value) {
        if (value == null) return "";
        // URLEncoder.encode uses '+' for spaces, but Ansible/Jinja2 typically uses %20
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String getName() {
        return "urlencode";
    }
}
