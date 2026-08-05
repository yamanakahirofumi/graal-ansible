package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.lib.filter.Filter;

/**
 * Robust ipaddr filter implementation validating IPv4 and IPv6 addresses.
 */
public class IpAddrFilter implements Filter {

    @Override
    public Object filter(Object var, JinjavaInterpreter interpreter, String... args) {
        if (var == null) {
            return null;
        }
        String ip = var.toString().trim();
        if (ip.isEmpty()) {
            return false;
        }

        if (isValidIP(ip)) {
            return ip;
        }
        return false;
    }

    private boolean isValidIP(String ip) {
        // Handle IPv4-mapped/compatible IPv6 addresses: e.g. ::ffff:192.168.1.1
        if (ip.contains(":") && ip.contains(".")) {
            int lastColon = ip.lastIndexOf(':');
            if (lastColon == -1) {
                return false;
            }
            String ipv6Part = ip.substring(0, lastColon);
            String ipv4Part = ip.substring(lastColon + 1);
            if (ipv6Part.equals(":")) {
                ipv6Part = "::";
            } else if (ipv6Part.endsWith(":")) {
                ipv6Part += "0";
            }
            return isValidIPv6(ipv6Part) && isValidIPv4(ipv4Part);
        }

        if (ip.contains(".")) {
            return isValidIPv4(ip);
        }

        if (ip.contains(":")) {
            return isValidIPv6(ip);
        }

        return false;
    }

    private boolean isValidIPv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            // Reject leading zeros to avoid octal confusion: e.g. "192.168.01.1" -> false
            if (part.length() > 1 && part.startsWith("0")) {
                return false;
            }
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidIPv6(String ip) {
        if (!ip.contains(":")) {
            return false;
        }
        // At most one double-colon "::" is allowed
        int firstDoubleColon = ip.indexOf("::");
        if (firstDoubleColon != -1) {
            int secondDoubleColon = ip.indexOf("::", firstDoubleColon + 2);
            if (secondDoubleColon != -1) {
                return false;
            }
        }
        // Triple colon is never allowed
        if (ip.contains(":::")) {
            return false;
        }
        if (ip.startsWith(":") && !ip.startsWith("::")) {
            return false;
        }
        if (ip.endsWith(":") && !ip.endsWith("::")) {
            return false;
        }

        String[] parts = ip.split(":", -1);
        if (parts.length > 8) {
            return false;
        }

        for (String part : parts) {
            if (!part.isEmpty()) {
                if (part.length() > 4) {
                    return false;
                }
                for (int j = 0; j < part.length(); j++) {
                    char c = part.charAt(j);
                    boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                    if (!isHex) {
                        return false;
                    }
                }
            }
        }

        boolean hasDoubleColon = ip.contains("::");
        if (hasDoubleColon) {
            int nonEmptyParts = 0;
            for (String part : parts) {
                if (!part.isEmpty()) {
                    nonEmptyParts++;
                }
            }
            return nonEmptyParts <= 7;
        } else {
            return parts.length == 8;
        }
    }

    @Override
    public String getName() {
        return "ipaddr";
    }
}