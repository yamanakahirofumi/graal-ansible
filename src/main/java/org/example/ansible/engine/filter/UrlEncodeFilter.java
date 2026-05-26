package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filter that URL encodes strings or dictionaries.
 * Usage: {{ value | urlencode }}
 */
public class UrlEncodeFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return "";

        if (var instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> {
                        String key = entry.getKey().toString();
                        String value = entry.getValue() != null ? entry.getValue().toString() : "";
                        return encode(key) + "=" + encode(value);
                    })
                    .collect(Collectors.joining("&"));
        } else {
            return encode(var.toString());
        }
    }

    private String encode(String s) {
        try {
            // URLEncoder.encode encodes space as '+', but Jinja2/Ansible urlencode uses '%20'
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    @Override
    public String getName() {
        return "urlencode";
    }
}
