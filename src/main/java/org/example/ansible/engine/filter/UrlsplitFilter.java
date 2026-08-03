package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * Filter that splits a URL into its components, mimicking Python's urllib.parse.urlsplit.
 */
public class UrlsplitFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String urlStr = var.toString();

        try {
            URI uri = new URI(urlStr);
            Map<String, Object> components = new HashMap<>();

            components.put("scheme", emptyIfNull(uri.getScheme()));
            components.put("netloc", emptyIfNull(uri.getRawAuthority()));
            components.put("path", emptyIfNull(uri.getRawPath()));
            components.put("query", emptyIfNull(uri.getRawQuery()));
            components.put("fragment", emptyIfNull(uri.getRawFragment()));

            String userInfo = uri.getUserInfo();
            String username = null;
            String password = null;
            if (userInfo != null) {
                int colonIdx = userInfo.indexOf(':');
                if (colonIdx != -1) {
                    username = userInfo.substring(0, colonIdx);
                    password = userInfo.substring(colonIdx + 1);
                } else {
                    username = userInfo;
                }
            }
            components.put("username", username);
            components.put("password", password);
            components.put("hostname", emptyIfNull(uri.getHost()));

            int port = uri.getPort();
            components.put("port", port == -1 ? null : port);

            if (args != null && args.length > 0) {
                String requested = args[0];
                if (components.containsKey(requested)) {
                    Object val = components.get(requested);
                    return val == null ? "" : val;
                }
                return "";
            }

            return components;
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to parse URL: " + urlStr, e);
        }
    }

    private String emptyIfNull(String val) {
        return val == null ? "" : val;
    }

    @Override
    public String getName() {
        return "urlsplit";
    }
}
