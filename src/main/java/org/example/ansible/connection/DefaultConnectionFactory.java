package org.example.ansible.connection;

import org.example.ansible.inventory.Host;
import java.util.Map;

/**
 * Default implementation of ConnectionFactory.
 */
public class DefaultConnectionFactory implements ConnectionFactory {

    @Override
    public Connection createConnection(Host host, Map<String, Object> variables) {
        String connectionType = (String) variables.getOrDefault("ansible_connection", "ssh");

        // Special case for localhost if not specified
        if ("localhost".equals(host.name()) || "127.0.0.1".equals(host.name())) {
            if (!variables.containsKey("ansible_connection")) {
                connectionType = "local";
            }
        }

        if ("local".equals(connectionType)) {
            return new LocalConnection();
        } else if ("docker".equals(connectionType)) {
            String containerName = (String) variables.getOrDefault("ansible_host", host.name());
            String user = (String) variables.get("ansible_user");
            return new DockerConnection(containerName, user);
        } else if ("ssh".equals(connectionType) || "smart".equals(connectionType)) {
            String ansibleHost = (String) variables.getOrDefault("ansible_host", host.name());
            int port = 22;
            Object portVar = variables.get("ansible_port");
            if (portVar instanceof Number n) {
                port = n.intValue();
            } else if (portVar instanceof String s) {
                port = Integer.parseInt(s);
            }

            String user = (String) variables.get("ansible_user");
            String password = (String) variables.get("ansible_password");

            return new SshConnection(ansibleHost, port, user, password);
        } else if ("winrm".equals(connectionType)) {
            String ansibleHost = (String) variables.getOrDefault("ansible_host", host.name());
            String scheme = (String) variables.getOrDefault("ansible_winrm_scheme", "http");
            boolean useHttps = "https".equalsIgnoreCase(scheme);

            int port = useHttps ? 5986 : 5985;
            Object portVar = variables.get("ansible_port");
            if (portVar instanceof Number n) {
                port = n.intValue();
            } else if (portVar instanceof String s) {
                port = Integer.parseInt(s);
            }

            String user = (String) variables.get("ansible_user");
            String password = (String) variables.get("ansible_password");

            String certValidation = (String) variables.getOrDefault("ansible_winrm_server_cert_validation", "ignore");
            boolean disableCertificateChecks = "ignore".equalsIgnoreCase(certValidation);

            return new WinRMConnection(ansibleHost, port, user, password, useHttps, disableCertificateChecks);
        }

        throw new IllegalArgumentException("Unsupported connection type: " + connectionType);
    }
}
