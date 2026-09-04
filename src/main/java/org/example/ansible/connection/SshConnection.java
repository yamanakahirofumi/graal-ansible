package org.example.ansible.connection;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;
import org.apache.sshd.common.util.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of Connection for SSH execution using Apache SSHD.
 * Enhanced to support private key file authentication and cascading SSH Jump Hosts / Bastions.
 */
public class SshConnection implements Connection {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String privateKeyFile;
    private final List<BastionConfig> bastionConfigs;

    private SshClient client;
    private ClientSession session;
    private final Duration timeout = Duration.ofSeconds(30);

    private static class ActiveBastion {
        final ClientSession session;
        final org.apache.sshd.common.util.net.SshdSocketAddress localAddr;

        ActiveBastion(ClientSession session, org.apache.sshd.common.util.net.SshdSocketAddress localAddr) {
            this.session = session;
            this.localAddr = localAddr;
        }
    }

    private final List<ActiveBastion> activeBastions = new ArrayList<>();

    public SshConnection(String host, int port, String username, String password) {
        this(host, port, username, password, null, Collections.emptyList());
    }

    public SshConnection(String host, int port, String username, String password, String privateKeyFile, List<BastionConfig> bastionConfigs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyFile = privateKeyFile;
        this.bastionConfigs = bastionConfigs != null ? bastionConfigs : Collections.emptyList();
    }

    @Override
    public String getTransport() {
        return "ssh";
    }

    @Override
    public void connect() {
        try {
            client = SshClient.setUpDefaultClient();
            client.start();

            int n = bastionConfigs.size();
            for (int i = 0; i < n; i++) {
                BastionConfig bastion = bastionConfigs.get(i);
                ClientSession bSession;
                try {
                    if (i == 0) {
                        bSession = client.connect(bastion.user(), bastion.host(), bastion.port())
                                .verify(timeout)
                                .getSession();
                    } else {
                        int prevLocalPort = activeBastions.get(i - 1).localAddr.getPort();
                        bSession = client.connect(bastion.user(), "localhost", prevLocalPort)
                                .verify(timeout)
                                .getSession();
                    }
                } catch (IOException e) {
                    throw new UnreachableException("[Bastion] Failed to connect to bastion " + bastion.host() + ":" + bastion.port(), e);
                }

                try {
                    if (bastion.password() != null) {
                        bSession.addPasswordIdentity(bastion.password());
                    }
                    if (bastion.privateKeyFile() != null) {
                        addPrivateKeyIfPresent(bSession, bastion.privateKeyFile());
                    }
                    bSession.auth().verify(timeout);
                } catch (IOException e) {
                    try {
                        bSession.close();
                    } catch (IOException ignored) {}
                    throw new UnreachableException("[Bastion Auth Failed] Failed to authenticate to bastion " + bastion.host() + ":" + bastion.port(), e);
                }

                String destHost;
                int destPort;
                if (i == n - 1) {
                    destHost = this.host;
                    destPort = this.port;
                } else {
                    BastionConfig nextBastion = bastionConfigs.get(i + 1);
                    destHost = nextBastion.host();
                    destPort = nextBastion.port();
                }

                org.apache.sshd.common.util.net.SshdSocketAddress localAddr = new org.apache.sshd.common.util.net.SshdSocketAddress("localhost", 0);
                org.apache.sshd.common.util.net.SshdSocketAddress remoteAddr = new org.apache.sshd.common.util.net.SshdSocketAddress(destHost, destPort);
                org.apache.sshd.common.util.net.SshdSocketAddress boundAddr;
                try {
                    boundAddr = bSession.startLocalPortForwarding(localAddr, remoteAddr);
                } catch (IOException e) {
                    try {
                        bSession.close();
                    } catch (IOException ignored) {}
                    throw new UnreachableException("[Bastion Port Forward Denied] Failed to start local port forwarding on bastion " + bastion.host() + ":" + bastion.port(), e);
                }

                activeBastions.add(new ActiveBastion(bSession, boundAddr));
            }

            // Finally, connect to the target host (through the last tunnel port if bastions present)
            try {
                if (n > 0) {
                    int lastLocalPort = activeBastions.get(n - 1).localAddr.getPort();
                    session = client.connect(username, "localhost", lastLocalPort)
                            .verify(timeout)
                            .getSession();
                } else {
                    session = client.connect(username, host, port)
                            .verify(timeout)
                            .getSession();
                }
            } catch (IOException e) {
                throw new UnreachableException("Failed to connect to " + host + ":" + port, e);
            }

            try {
                if (password != null) {
                    session.addPasswordIdentity(password);
                }
                if (privateKeyFile != null) {
                    addPrivateKeyIfPresent(session, privateKeyFile);
                }
                session.auth().verify(timeout);
            } catch (IOException e) {
                if (session != null) {
                    try {
                        session.close();
                    } catch (IOException ignored) {}
                }
                throw new UnreachableException("Failed to authenticate to " + host + ":" + port, e);
            }

        } catch (Throwable t) {
            // Cleanup on any failure during connection setup
            close();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Error during connection setup", t);
        }
    }

    private void addPrivateKeyIfPresent(ClientSession sess, String keyFile) {
        if (keyFile != null && !keyFile.isEmpty()) {
            try {
                Path path = java.nio.file.Paths.get(keyFile);
                if (java.nio.file.Files.exists(path)) {
                    org.apache.sshd.common.keyprovider.FileKeyPairProvider provider =
                        new org.apache.sshd.common.keyprovider.FileKeyPairProvider(path);
                    Iterable<java.security.KeyPair> keyPairs = provider.loadKeys(null);
                    if (keyPairs != null) {
                        for (java.security.KeyPair kp : keyPairs) {
                            sess.addPublicKeyIdentity(kp);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore key loading errors
            }
        }
    }

    @Override
    public ConnectionResult execCommand(String command, BecomeContext becomeContext, java.util.Map<String, String> environment) {
        String effectiveCommand = command;
        if (becomeContext != null && becomeContext.become()) {
            String method = becomeContext.becomeMethod();
            if (method == null || "sudo".equals(method)) {
                StringBuilder sb = new StringBuilder("sudo -H -S -n -p BECOME-PROMPT ");
                if (becomeContext.becomeUser() != null) {
                    sb.append("-u ").append(becomeContext.becomeUser()).append(" ");
                }
                if (becomeContext.becomeFlags() != null && !becomeContext.becomeFlags().isEmpty()) {
                    sb.append(becomeContext.becomeFlags()).append(" ");
                }
                // Wrap original command in single quotes to pass as a single argument to shell
                sb.append("/bin/sh -c '").append(command.replace("'", "'\\''")).append("'");
                effectiveCommand = sb.toString();
            } else if ("su".equals(method)) {
                StringBuilder sb = new StringBuilder("su ");
                if (becomeContext.becomeUser() != null) {
                    sb.append(becomeContext.becomeUser()).append(" ");
                }
                sb.append("-c '").append(command.replace("'", "'\\''")).append("'");
                effectiveCommand = sb.toString();
            }
        }

        if (session == null || !session.isOpen()) {
            throw new UnreachableException("SSH session is not open");
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ByteArrayOutputStream err = new ByteArrayOutputStream();
             ChannelExec channel = session.createExecChannel(effectiveCommand)) {

            if (becomeContext != null && becomeContext.become() && becomeContext.becomePassword() != null) {
                try (java.io.OutputStream os = channel.getInvertedIn()) {
                    os.write((becomeContext.becomePassword() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            if (environment != null) {
                for (java.util.Map.Entry<String, String> entry : environment.entrySet()) {
                    channel.setEnv(entry.getKey(), entry.getValue());
                }
            }
            channel.setOut(out);
            channel.setErr(err);
            channel.open().verify(timeout);
            
            // Wait for channel to close or timeout
            channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), timeout);
            
            Integer exitStatus = channel.getExitStatus();
            return new ConnectionResult(
                out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8),
                exitStatus != null ? exitStatus : -1
            );
        } catch (IOException e) {
            throw new UnreachableException("Command execution failed due to connection error: " + e.getMessage(), e);
        }
    }

    @Override
    public void putFile(Path localPath, String remotePath) {
        try {
            ScpClientCreator creator = ScpClientCreator.instance();
            ScpClient scpClient = creator.createScpClient(session);
            scpClient.upload(localPath, remotePath, ScpClient.Option.PreserveAttributes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file " + localPath + " to " + remotePath, e);
        }
    }

    @Override
    public void fetchFile(String remotePath, Path localPath) {
        try {
            ScpClientCreator creator = ScpClientCreator.instance();
            ScpClient scpClient = creator.createScpClient(session);
            scpClient.download(remotePath, localPath, ScpClient.Option.PreserveAttributes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download file " + remotePath + " to " + localPath, e);
        }
    }

    @Override
    public void close() {
        // 1. Close target session first
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {}
            session = null;
        }

        // 2. Stop local port forwards and close bastion sessions in REVERSE order of setup
        for (int i = activeBastions.size() - 1; i >= 0; i--) {
            ActiveBastion ab = activeBastions.get(i);
            if (ab.session != null) {
                if (ab.localAddr != null) {
                    try {
                        ab.session.stopLocalPortForwarding(ab.localAddr);
                    } catch (Exception ignored) {}
                }
                try {
                    ab.session.close();
                } catch (Exception ignored) {}
            }
        }
        activeBastions.clear();

        // 3. Stop client last
        if (client != null) {
            try {
                client.stop();
            } catch (Exception ignored) {}
            client = null;
        }
    }
}
