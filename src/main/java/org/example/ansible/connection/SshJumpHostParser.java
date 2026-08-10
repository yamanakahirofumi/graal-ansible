package org.example.ansible.connection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for SSH Bastion / Jump Host configuration.
 */
public class SshJumpHostParser {

    private static final Pattern PROXY_JUMP_PATTERN = Pattern.compile(
        "-o\\s+ProxyJump\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s\\n\\r]+))",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PROXY_COMMAND_PATTERN = Pattern.compile(
        "ProxyCommand\\s+(.+)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Parses the variables map to extract Bastion configurations.
     */
    public static List<BastionConfig> getBastionConfigs(Map<String, Object> variables) {
        List<BastionConfig> configs = new ArrayList<>();

        // 1. Direct variables (highest priority, unique extension)
        if (variables.containsKey("ansible_bastion_host")) {
            String host = (String) variables.get("ansible_bastion_host");
            if (host != null && !host.trim().isEmpty()) {
                int port = 22;
                Object portVar = variables.get("ansible_bastion_port");
                if (portVar instanceof Number n) {
                    port = n.intValue();
                } else if (portVar instanceof String s) {
                    try {
                        port = Integer.parseInt(s);
                    } catch (NumberFormatException ignored) {}
                }

                String user = (String) variables.get("ansible_bastion_user");
                if (user == null || user.trim().isEmpty()) {
                    user = (String) variables.get("ansible_user");
                }
                String password = (String) variables.get("ansible_bastion_password");
                String privateKeyFile = (String) variables.get("ansible_bastion_private_key_file");

                configs.add(new BastionConfig(host, port, user, password, privateKeyFile));
                return configs;
            }
        }

        // 2. Check ansible_ssh_extra_args and ansible_ssh_common_args
        String extraArgs = (String) variables.get("ansible_ssh_extra_args");
        String commonArgs = (String) variables.get("ansible_ssh_common_args");

        String args = null;
        if (extraArgs != null && !extraArgs.isEmpty()) {
            args = extraArgs;
        } else if (commonArgs != null && !commonArgs.isEmpty()) {
            args = commonArgs;
        }

        if (args != null) {
            // Check ProxyJump
            Matcher jumpMatcher = PROXY_JUMP_PATTERN.matcher(args);
            if (jumpMatcher.find()) {
                String value = jumpMatcher.group(1);
                if (value == null) {
                    value = jumpMatcher.group(2);
                }
                if (value == null) {
                    value = jumpMatcher.group(3);
                }
                if (value != null) {
                    String defaultUser = (String) variables.get("ansible_user");
                    String defaultPassword = (String) variables.get("ansible_password");
                    String defaultKeyFile = (String) variables.get("ansible_ssh_private_key_file");
                    String[] hops = value.split(",");
                    for (String hop : hops) {
                        configs.add(parseHop(hop, defaultUser, defaultPassword, defaultKeyFile));
                    }
                    return configs;
                }
            }

            // Check ProxyCommand
            Matcher cmdMatcher = PROXY_COMMAND_PATTERN.matcher(args);
            if (cmdMatcher.find()) {
                String cmd = cmdMatcher.group(1);
                if (cmd != null) {
                    String defaultUser = (String) variables.get("ansible_user");
                    String defaultPassword = (String) variables.get("ansible_password");
                    String defaultKeyFile = (String) variables.get("ansible_ssh_private_key_file");
                    BastionConfig cfg = parseProxyCommand(cmd, defaultUser, defaultPassword, defaultKeyFile);
                    if (cfg != null) {
                        configs.add(cfg);
                        return configs;
                    }
                }
            }
        }

        return configs;
    }

    private static BastionConfig parseHop(String hop, String defaultUser, String defaultPassword, String defaultKeyFile) {
        String user = defaultUser;
        String host = "";
        int port = 22;

        String temp = hop.trim();
        if (temp.contains("@")) {
            int atIdx = temp.indexOf('@');
            user = temp.substring(0, atIdx);
            temp = temp.substring(atIdx + 1);
        }
        if (temp.contains(":")) {
            int colonIdx = temp.indexOf(':');
            host = temp.substring(0, colonIdx);
            try {
                port = Integer.parseInt(temp.substring(colonIdx + 1));
            } catch (NumberFormatException ignored) {}
        } else {
            host = temp;
        }
        return new BastionConfig(host, port, user, defaultPassword, defaultKeyFile);
    }

    private static BastionConfig parseProxyCommand(String command, String defaultUser, String defaultPassword, String defaultKeyFile) {
        String[] parts = command.trim().split("\\s+");
        String userAndHost = null;
        int port = 22;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equalsIgnoreCase("-p") && i + 1 < parts.length) {
                try {
                    port = Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException ignored) {}
                i++; // skip port number
            } else if (part.startsWith("-")) {
                // skip options (e.g. -q, -W)
            } else if (part.equalsIgnoreCase("ssh") || part.contains("%h")) {
                // skip command name and placeholder
            } else {
                userAndHost = part;
            }
        }
        if (userAndHost != null) {
            return parseHop(userAndHost, defaultUser, defaultPassword, defaultKeyFile).withPort(port);
        }
        return null;
    }
}
