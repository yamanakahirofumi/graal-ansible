package org.example.ansible.connection;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshConnectionTest {

    private SshConnection connection;
    private SshClient mockClient;
    private ClientSession mockSession;
    private MockedStatic<SshClient> mockedSshClient;

    @BeforeEach
    void setUp() throws IOException {
        mockedSshClient = mockStatic(SshClient.class);
        mockClient = mock(SshClient.class);
        mockSession = mock(ClientSession.class);

        mockedSshClient.when(SshClient::setUpDefaultClient).thenReturn(mockClient);

        ConnectFuture mockConnectFuture = mock(ConnectFuture.class);
        when(mockClient.connect(anyString(), anyString(), anyInt())).thenReturn(mockConnectFuture);
        when(mockConnectFuture.verify(any(Duration.class))).thenReturn(mockConnectFuture);
        when(mockConnectFuture.getSession()).thenReturn(mockSession);

        AuthFuture mockAuthFuture = mock(AuthFuture.class);
        when(mockSession.auth()).thenReturn(mockAuthFuture);
        when(mockAuthFuture.verify(any(Duration.class))).thenReturn(mockAuthFuture);

        connection = new SshConnection("localhost", 22, "testuser", "testpassword");
    }

    @AfterEach
    void tearDown() {
        if (mockedSshClient != null) {
            mockedSshClient.close();
        }
    }

    @Test
    void testConnectSuccess() {
        assertDoesNotThrow(() -> connection.connect());
    }

    @Test
    void testConnectFailure() throws IOException {
        reset(mockClient);
        when(mockClient.connect(anyString(), anyString(), anyInt())).thenThrow(new IOException("Connection refused"));

        assertThrows(UnreachableException.class, () -> connection.connect());
    }

    @Test
    void testExecCommandSessionNotOpen() {
        when(mockSession.isOpen()).thenReturn(false);
        // We need to connect first so session is populated
        connection.connect();

        assertThrows(UnreachableException.class, () ->
            connection.execCommand("echo hello", BecomeContext.empty(), null)
        );
    }

    @Test
    void testExecCommandSuccess() throws IOException {
        connection.connect();
        when(mockSession.isOpen()).thenReturn(true);

        ChannelExec mockChannel = mock(ChannelExec.class);
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);

        OpenFuture mockOpenFuture = mock(OpenFuture.class);
        when(mockChannel.open()).thenReturn(mockOpenFuture);
        when(mockOpenFuture.verify(any(Duration.class))).thenReturn(mockOpenFuture);

        when(mockChannel.getExitStatus()).thenReturn(0);

        doAnswer(invocation -> {
            ByteArrayOutputStream out = invocation.getArgument(0);
            out.write("hello from mock ssh\n".getBytes());
            return null;
        }).when(mockChannel).setOut(any(ByteArrayOutputStream.class));

        ConnectionResult result = connection.execCommand("echo hello", BecomeContext.empty(), null);

        assertNotNull(result);
        assertEquals(0, result.exitCode());
        assertEquals("hello from mock ssh", result.stdout().trim());
        assertTrue(result.stderr().isEmpty());

        verify(mockSession).createExecChannel("echo hello");
    }

    @Test
    void testExecCommandFailure() throws IOException {
        connection.connect();
        when(mockSession.isOpen()).thenReturn(true);

        ChannelExec mockChannel = mock(ChannelExec.class);
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);

        OpenFuture mockOpenFuture = mock(OpenFuture.class);
        when(mockChannel.open()).thenReturn(mockOpenFuture);
        when(mockOpenFuture.verify(any(Duration.class))).thenReturn(mockOpenFuture);

        when(mockChannel.getExitStatus()).thenReturn(42);

        doAnswer(invocation -> {
            ByteArrayOutputStream err = invocation.getArgument(0);
            err.write("error from mock ssh\n".getBytes());
            return null;
        }).when(mockChannel).setErr(any(ByteArrayOutputStream.class));

        ConnectionResult result = connection.execCommand("invalid_command", BecomeContext.empty(), null);

        assertNotNull(result);
        assertEquals(42, result.exitCode());
        assertTrue(result.stdout().isEmpty());
        assertEquals("error from mock ssh", result.stderr().trim());
    }

    @Test
    void testExecCommandBecomeSudo() throws IOException {
        connection.connect();
        when(mockSession.isOpen()).thenReturn(true);

        ChannelExec mockChannel = mock(ChannelExec.class);
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);

        OpenFuture mockOpenFuture = mock(OpenFuture.class);
        when(mockChannel.open()).thenReturn(mockOpenFuture);
        when(mockOpenFuture.verify(any(Duration.class))).thenReturn(mockOpenFuture);

        when(mockChannel.getExitStatus()).thenReturn(0);

        // Dummy output stream to avoid NullPointerException if written to
        ByteArrayOutputStream dummyIn = new ByteArrayOutputStream();
        when(mockChannel.getInvertedIn()).thenReturn(dummyIn);

        BecomeContext become = new BecomeContext(true, "sudo", "root", "become_password", null);
        connection.execCommand("whoami", become, null);

        // Verify command was wrapped for sudo
        verify(mockSession).createExecChannel(contains("sudo"));
        verify(mockSession).createExecChannel(contains("whoami"));
    }

    @Test
    void testExecCommandEnvironment() throws IOException {
        connection.connect();
        when(mockSession.isOpen()).thenReturn(true);

        ChannelExec mockChannel = mock(ChannelExec.class);
        when(mockSession.createExecChannel(anyString())).thenReturn(mockChannel);

        OpenFuture mockOpenFuture = mock(OpenFuture.class);
        when(mockChannel.open()).thenReturn(mockOpenFuture);
        when(mockOpenFuture.verify(any(Duration.class))).thenReturn(mockOpenFuture);

        when(mockChannel.getExitStatus()).thenReturn(0);

        connection.execCommand("echo $MY_VAR", BecomeContext.empty(), Map.of("MY_VAR", "my_value"));

        verify(mockChannel).setEnv("MY_VAR", "my_value");
    }

    @Test
    void testPutFile() throws IOException {
        connection.connect();

        try (MockedStatic<ScpClientCreator> mockedScpCreator = mockStatic(ScpClientCreator.class)) {
            ScpClientCreator mockCreator = mock(ScpCreatorWrapper.class);
            mockedScpCreator.when(ScpClientCreator::instance).thenReturn(mockCreator);

            ScpClient mockScpClient = mock(ScpClient.class);
            when(mockCreator.createScpClient(any(ClientSession.class))).thenReturn(mockScpClient);

            Path localPath = Paths.get("src.txt");
            connection.putFile(localPath, "/remote/path.txt");

            verify(mockScpClient).upload(eq(localPath), eq("/remote/path.txt"), any(ScpClient.Option[].class));
        }
    }

    @Test
    void testFetchFile() throws IOException {
        connection.connect();

        try (MockedStatic<ScpClientCreator> mockedScpCreator = mockStatic(ScpClientCreator.class)) {
            ScpClientCreator mockCreator = mock(ScpCreatorWrapper.class);
            mockedScpCreator.when(ScpClientCreator::instance).thenReturn(mockCreator);

            ScpClient mockScpClient = mock(ScpClient.class);
            when(mockCreator.createScpClient(any(ClientSession.class))).thenReturn(mockScpClient);

            Path localPath = Paths.get("dest.txt");
            connection.fetchFile("/remote/path.txt", localPath);

            verify(mockScpClient).download(eq("/remote/path.txt"), eq(localPath), any(ScpClient.Option[].class));
        }
    }

    @Test
    void testClose() throws IOException {
        connection.connect();
        connection.close();

        verify(mockSession).close();
        verify(mockClient).stop();
    }

    // --- SSH Jump Host / Bastion Tests ---

    @Test
    void testConnectWithBastionSuccess() throws IOException {
        BastionConfig bastion = new BastionConfig("bastion.host", 22, "bastionuser", "bastionpass", null);
        List<BastionConfig> bastions = List.of(bastion);

        // Connection with 1 bastion
        connection = new SshConnection("target.host", 22, "targetuser", "targetpass", null, bastions);

        // Mock connect for bastion
        ClientSession mockBastionSession = mock(ClientSession.class);
        ConnectFuture mockBastionConnectFuture = mock(ConnectFuture.class);
        when(mockClient.connect("bastionuser", "bastion.host", 22)).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.verify(any(Duration.class))).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.getSession()).thenReturn(mockBastionSession);

        AuthFuture mockBastionAuthFuture = mock(AuthFuture.class);
        when(mockBastionSession.auth()).thenReturn(mockBastionAuthFuture);
        when(mockBastionAuthFuture.verify(any(Duration.class))).thenReturn(mockBastionAuthFuture);

        // Mock port forward
        org.apache.sshd.common.util.net.SshdSocketAddress mockLocalAddr = new org.apache.sshd.common.util.net.SshdSocketAddress("localhost", 12345);
        when(mockBastionSession.startLocalPortForwarding(any(), any())).thenReturn(mockLocalAddr);

        // Mock connect for target through localhost:12345
        ConnectFuture mockTargetConnectFuture = mock(ConnectFuture.class);
        when(mockClient.connect("targetuser", "localhost", 12345)).thenReturn(mockTargetConnectFuture);
        when(mockTargetConnectFuture.verify(any(Duration.class))).thenReturn(mockTargetConnectFuture);
        when(mockTargetConnectFuture.getSession()).thenReturn(mockSession);

        assertDoesNotThrow(() -> connection.connect());

        verify(mockClient).connect("bastionuser", "bastion.host", 22);
        verify(mockClient).connect("targetuser", "localhost", 12345);
        verify(mockBastionSession).startLocalPortForwarding(any(), any());

        // Verify cascading cleanup in REVERSE order
        connection.close();
        verify(mockSession).close();
        verify(mockBastionSession).stopLocalPortForwarding(eq(mockLocalAddr));
        verify(mockBastionSession).close();
    }

    @Test
    void testConnectBastionUnreachable() throws IOException {
        BastionConfig bastion = new BastionConfig("bastion.host", 22, "bastionuser", "bastionpass", null);
        connection = new SshConnection("target.host", 22, "targetuser", "targetpass", null, List.of(bastion));

        when(mockClient.connect("bastionuser", "bastion.host", 22)).thenThrow(new IOException("Host unreachable"));

        UnreachableException ex = assertThrows(UnreachableException.class, () -> connection.connect());
        assertTrue(ex.getMessage().contains("[Bastion]"));
    }

    @Test
    void testConnectBastionAuthFailed() throws IOException {
        BastionConfig bastion = new BastionConfig("bastion.host", 22, "bastionuser", "bastionpass", null);
        connection = new SshConnection("target.host", 22, "targetuser", "targetpass", null, List.of(bastion));

        ClientSession mockBastionSession = mock(ClientSession.class);
        ConnectFuture mockBastionConnectFuture = mock(ConnectFuture.class);
        when(mockClient.connect("bastionuser", "bastion.host", 22)).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.verify(any(Duration.class))).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.getSession()).thenReturn(mockBastionSession);

        AuthFuture mockBastionAuthFuture = mock(AuthFuture.class);
        when(mockBastionSession.auth()).thenReturn(mockBastionAuthFuture);
        when(mockBastionAuthFuture.verify(any(Duration.class))).thenThrow(new IOException("Bad credentials"));

        UnreachableException ex = assertThrows(UnreachableException.class, () -> connection.connect());
        assertTrue(ex.getMessage().contains("[Bastion Auth Failed]"));
        verify(mockBastionSession).close();
    }

    @Test
    void testConnectBastionPortForwardDenied() throws IOException {
        BastionConfig bastion = new BastionConfig("bastion.host", 22, "bastionuser", "bastionpass", null);
        connection = new SshConnection("target.host", 22, "targetuser", "targetpass", null, List.of(bastion));

        ClientSession mockBastionSession = mock(ClientSession.class);
        ConnectFuture mockBastionConnectFuture = mock(ConnectFuture.class);
        when(mockClient.connect("bastionuser", "bastion.host", 22)).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.verify(any(Duration.class))).thenReturn(mockBastionConnectFuture);
        when(mockBastionConnectFuture.getSession()).thenReturn(mockBastionSession);

        AuthFuture mockBastionAuthFuture = mock(AuthFuture.class);
        when(mockBastionSession.auth()).thenReturn(mockBastionAuthFuture);
        when(mockBastionAuthFuture.verify(any(Duration.class))).thenReturn(mockBastionAuthFuture);

        when(mockBastionSession.startLocalPortForwarding(any(), any())).thenThrow(new IOException("Port forwarding disabled"));

        UnreachableException ex = assertThrows(UnreachableException.class, () -> connection.connect());
        assertTrue(ex.getMessage().contains("[Bastion Port Forward Denied]"));
        verify(mockBastionSession).close();
    }

    // --- Parser Tests ---

    @Test
    void testSshJumpHostParserDirectVariables() {
        Map<String, Object> variables = Map.of(
            "ansible_bastion_host", "bastion.direct",
            "ansible_bastion_port", 2222,
            "ansible_bastion_user", "directuser",
            "ansible_bastion_password", "directpass",
            "ansible_bastion_private_key_file", "/path/to/directkey",
            "ansible_user", "targetuser",
            "ansible_password", "targetpass"
        );

        List<BastionConfig> configs = SshJumpHostParser.getBastionConfigs(variables);
        assertEquals(1, configs.size());
        BastionConfig cfg = configs.get(0);
        assertEquals("bastion.direct", cfg.host());
        assertEquals(2222, cfg.port());
        assertEquals("directuser", cfg.user());
        assertEquals("directpass", cfg.password());
        assertEquals("/path/to/directkey", cfg.privateKeyFile());
    }

    @Test
    void testSshJumpHostParserProxyJump() {
        Map<String, Object> variables = Map.of(
            "ansible_ssh_common_args", "-o ProxyJump=jumpuser@jumphost:3333",
            "ansible_user", "targetuser",
            "ansible_password", "targetpass",
            "ansible_ssh_private_key_file", "/path/to/targetkey"
        );

        List<BastionConfig> configs = SshJumpHostParser.getBastionConfigs(variables);
        assertEquals(1, configs.size());
        BastionConfig cfg = configs.get(0);
        assertEquals("jumphost", cfg.host());
        assertEquals(3333, cfg.port());
        assertEquals("jumpuser", cfg.user());
        assertEquals("targetpass", cfg.password());
        assertEquals("/path/to/targetkey", cfg.privateKeyFile());
    }

    @Test
    void testSshJumpHostParserProxyJumpMultiHop() {
        Map<String, Object> variables = Map.of(
            "ansible_ssh_extra_args", "-o ProxyJump=\"user1@hop1:1001,user2@hop2:1002\"",
            "ansible_user", "targetuser",
            "ansible_password", "targetpass",
            "ansible_ssh_private_key_file", "/path/to/targetkey"
        );

        List<BastionConfig> configs = SshJumpHostParser.getBastionConfigs(variables);
        assertEquals(2, configs.size());

        BastionConfig cfg1 = configs.get(0);
        assertEquals("hop1", cfg1.host());
        assertEquals(1001, cfg1.port());
        assertEquals("user1", cfg1.user());

        BastionConfig cfg2 = configs.get(1);
        assertEquals("hop2", cfg2.host());
        assertEquals(1002, cfg2.port());
        assertEquals("user2", cfg2.user());
    }

    @Test
    void testSshJumpHostParserProxyCommand() {
        Map<String, Object> variables = Map.of(
            "ansible_ssh_common_args", "ProxyCommand ssh -q -W %h:%p proxyuser@proxyhost -p 4444",
            "ansible_user", "targetuser",
            "ansible_password", "targetpass"
        );

        List<BastionConfig> configs = SshJumpHostParser.getBastionConfigs(variables);
        assertEquals(1, configs.size());
        BastionConfig cfg = configs.get(0);
        assertEquals("proxyhost", cfg.host());
        assertEquals(4444, cfg.port());
        assertEquals("proxyuser", cfg.user());
    }

    // A helper interface/class wrapper to assist in matching creator creation safely.
    interface ScpCreatorWrapper extends ScpClientCreator {}
}
