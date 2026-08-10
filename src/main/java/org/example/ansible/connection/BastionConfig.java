package org.example.ansible.connection;

public record BastionConfig(
    String host,
    int port,
    String user,
    String password,
    String privateKeyFile
) {
    public BastionConfig withPort(int newPort) {
        return new BastionConfig(host, newPort, user, password, privateKeyFile);
    }
}
