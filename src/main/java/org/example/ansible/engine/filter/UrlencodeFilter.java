package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filter that URL encodes a string or a map/list into a query string.
 * It matches Ansible's urlencode filter behavior.
 */
public class UrlencodeFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) {
            return "";
        }

        if (var instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> encodeQueryParam(entry.getKey()) + "=" + encodeQueryParam(entry.getValue()))
                    .collect(Collectors.joining("&"));
        }

        if (var instanceof List<?> list) {
            if (list.isEmpty()) return "";

            boolean allPairs = list.stream().allMatch(item -> item instanceof List<?> pair && pair.size() == 2);
            if (allPairs) {
                return list.stream()
                        .map(item -> {
                            List<?> pair = (List<?>) item;
                            return encodeQueryParam(pair.get(0)) + "=" + encodeQueryParam(pair.get(1));
                        })
                        .collect(Collectors.joining("&"));
            }
        }

        return encodeString(var.toString());
    }

    private String encodeQueryParam(Object obj) {
        if (obj == null) return "";
        return URLEncoder.encode(obj.toString(), StandardCharsets.UTF_8);
    }

    private String encodeString(String s) {
        // For strings, we want space to be %20.
        // URLEncoder.encode uses '+' for space.
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    @Override
    public String getName() {
        return "urlencode";
    }
}
