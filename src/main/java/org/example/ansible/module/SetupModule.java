package org.example.ansible.module;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.graalvm.polyglot.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * SetupModule gathers facts from the target node.
 */
public class SetupModule implements Module {

    @Override
    public TaskResult execute(Map<String, Object> args, BecomeContext becomeContext, Context context) {
        Connection connection = TaskExecutor.getCurrentConnection();
        if (connection == null) {
            connection = new LocalConnection();
        }

        Map<String, Object> facts = new HashMap<>();
        Map<String, String> env = TaskExecutor.getCurrentEnvironment();

        // Basic uname facts
        facts.put("ansible_system", runCommand(connection, "uname -s", becomeContext, env).trim());
        facts.put("ansible_architecture", runCommand(connection, "uname -m", becomeContext, env).trim());
        facts.put("ansible_hostname", runCommand(connection, "uname -n", becomeContext, env).trim());
        facts.put("ansible_kernel", runCommand(connection, "uname -r", becomeContext, env).trim());

        // OS Family and Distribution
        String system = (String) facts.get("ansible_system");
        if ("Linux".equals(system)) {
            parseOsRelease(connection, becomeContext, env, facts);
        } else if ("Darwin".equals(system)) {
            facts.put("ansible_os_family", "Darwin");
            facts.put("ansible_distribution", "MacOSX");
            facts.put("ansible_distribution_version", runCommand(connection, "sw_vers -productVersion", becomeContext, env).trim());
        } else {
            facts.put("ansible_os_family", system);
            facts.put("ansible_distribution", system);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("ansible_facts", facts);
        result.put("changed", false);

        return TaskResult.success(result);
    }

    private String runCommand(Connection connection, String cmd, BecomeContext becomeContext, Map<String, String> env) {
        ConnectionResult res = connection.execCommand(cmd, becomeContext, env);
        return res.stdout();
    }

    private void parseOsRelease(Connection connection, BecomeContext becomeContext, Map<String, String> env, Map<String, Object> facts) {
        String content = runCommand(connection, "cat /etc/os-release", becomeContext, env);
        if (content == null || content.isBlank()) {
            facts.put("ansible_os_family", "Linux");
            return;
        }

        Map<String, String> osRelease = new HashMap<>();
        for (String line : content.split("\n")) {
            if (line.contains("=")) {
                String[] parts = line.split("=", 2);
                String key = parts[0];
                String value = parts[1].replace("\"", "").replace("'", "");
                osRelease.put(key, value);
            }
        }

        String id = osRelease.get("ID");
        facts.put("ansible_distribution", id);
        facts.put("ansible_distribution_version", osRelease.get("VERSION_ID"));

        if (id != null) {
            id = id.toLowerCase();
            if (id.equals("ubuntu") || id.equals("debian") || id.equals("linuxmint")) {
                facts.put("ansible_os_family", "Debian");
            } else if (id.equals("fedora") || id.equals("rhel") || id.equals("centos") || id.equals("rocky") || id.equals("almalinux") || id.equals("amzn")) {
                facts.put("ansible_os_family", "RedHat");
            } else if (id.equals("suse") || id.equals("sles") || id.equals("opensuse")) {
                facts.put("ansible_os_family", "Suse");
            } else {
                facts.put("ansible_os_family", "Linux");
            }
        } else {
            facts.put("ansible_os_family", "Linux");
        }
    }
}
