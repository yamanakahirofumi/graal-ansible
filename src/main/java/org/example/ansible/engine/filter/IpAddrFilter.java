package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Basic ipaddr filter placeholder.
 */
public class IpAddrFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String ip = var.toString();
        // Very basic IPv4 validation for demonstration
        return ip.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") ? ip : false;
    }

    @Override
    public String getName() {
        return "ipaddr";
    }
}
