package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filter that URL encodes a string or a map.
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

    private String encode(String s) {
        // URLEncoder.encode encodes space as '+', but Ansible's urlencode usually uses '%20'
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String getName() {
        return "urlencode";
    }
}
