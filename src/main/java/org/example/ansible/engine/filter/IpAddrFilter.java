package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Basic ipaddr filter. Validates IPv4 and IPv6 addresses.
 */
public class IpAddrFilter implements Filter {
    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) return null;
        String ip = var.toString().trim();
        if (isValidIPv4(ip) || isValidIPv6(ip)) {
            return ip;
        }
        return false;
    }

    private boolean isValidIPv4(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty()) return false;
            try {
                if (!part.matches("\\d+")) return false;
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
                if (part.length() > 1 && part.startsWith("0")) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidIPv6(String ip) {
        if (ip == null || ip.isEmpty()) return false;

        // Basic character check
        if (!ip.matches("^[0-9a-fA-F:]+$")) return false;

        // Cannot have more than two consecutive colons
        if (ip.contains(":::")) return false;

        // Cannot have more than one double colon
        int doubleColonIndex = ip.indexOf("::");
        if (doubleColonIndex != -1 && ip.indexOf("::", doubleColonIndex + 2) != -1) return false;

        // Count colons
        int colonCount = 0;
        for (char c : ip.toCharArray()) {
            if (c == ':') colonCount++;
        }
        if (colonCount < 2 || colonCount > 7) return false;

        // If there's no double colon, we must have exactly 7 colons
        if (doubleColonIndex == -1 && colonCount != 7) return false;

        String[] parts = ip.split(":", -1);
        if (parts.length > 8) return false;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                if (i == 0) {
                    if (i + 1 < parts.length && !parts[i + 1].isEmpty()) return false;
                } else if (i == parts.length - 1) {
                    if (i - 1 >= 0 && !parts[i - 1].isEmpty()) return false;
                }
            } else {
                if (part.length() > 4) return false;
                try {
                    Integer.parseInt(part, 16);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public String getName() {
        return "ipaddr";
    }
}
