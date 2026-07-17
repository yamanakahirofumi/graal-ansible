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

    // A helper interface/class wrapper to assist in matching creator creation safely.
    interface ScpCreatorWrapper extends ScpClientCreator {}
}
