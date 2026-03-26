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
import java.util.concurrent.TimeUnit;

/**
 * Implementation of Connection for SSH execution using Apache SSHD.
 */
public class SshConnection implements Connection {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private SshClient client;
    private ClientSession session;
    private final Duration timeout = Duration.ofSeconds(30);

    public SshConnection(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @Override
    public void connect() {
        try {
            client = SshClient.setUpDefaultClient();
            client.start();
            session = client.connect(username, host, port)
                    .verify(timeout)
                    .getSession();
            session.addPasswordIdentity(password);
            session.auth().verify(timeout);
        } catch (IOException e) {
            throw new UnreachableException("Failed to connect to " + host + ":" + port, e);
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
        try {
            if (session != null) {
                session.close();
            }
            if (client != null) {
                client.stop();
            }
        } catch (IOException e) {
            // Ignore close errors
        }
    }
}
