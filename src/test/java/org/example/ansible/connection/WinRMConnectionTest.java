package org.example.ansible.connection;

import io.cloudsoft.winrm4j.winrm.WinRmTool;
import io.cloudsoft.winrm4j.winrm.WinRmToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WinRMConnectionTest {

    private WinRmTool mockTool;
    private List<BuildToolCall> buildToolCalls;
    private TestableWinRMConnection connection;

    private static class BuildToolCall {
        String user;
        String password;

        BuildToolCall(String user, String password) {
            this.user = user;
            this.password = password;
        }
    }

    private class TestableWinRMConnection extends WinRMConnection {
        public TestableWinRMConnection(String host, int port, String user, String password) {
            super(host, port, user, password, false, true);
        }

        @Override
        protected WinRmTool buildTool(String user, String password) {
            buildToolCalls.add(new BuildToolCall(user, password));
            return mockTool;
        }
    }

    @BeforeEach
    void setUp() {
        mockTool = mock(WinRmTool.class);
        buildToolCalls = new ArrayList<>();
        connection = new TestableWinRMConnection("windows-host", 5985, "testuser", "testpassword");
    }

    @Test
    void testConnectSuccess() {
        WinRmToolResponse response = new WinRmToolResponse("connected", "", 0);
        when(mockTool.executePs(contains("Write-Output"))).thenReturn(response);

        assertDoesNotThrow(() -> connection.connect());

        verify(mockTool).executePs("Write-Output 'connected'");
        assertEquals(1, buildToolCalls.size());
        assertEquals("testuser", buildToolCalls.get(0).user);
        assertEquals("testpassword", buildToolCalls.get(0).password);
    }

    @Test
    void testConnectFailureStatusCode() {
        WinRmToolResponse response = new WinRmToolResponse("", "Connection refused or auth failed", 1);
        when(mockTool.executePs(contains("Write-Output"))).thenReturn(response);

        UnreachableException ex = assertThrows(UnreachableException.class, () -> connection.connect());
        assertTrue(ex.getMessage().contains("Failed to execute test command"));
    }

    @Test
    void testConnectFailureException() {
        when(mockTool.executePs(anyString())).thenThrow(new RuntimeException("Network issue"));

        UnreachableException ex = assertThrows(UnreachableException.class, () -> connection.connect());
        assertTrue(ex.getMessage().contains("WinRM connection failed"));
    }

    @Test
    void testExecCommandSuccess() {
        WinRmToolResponse response = new WinRmToolResponse("hello world", "", 0);
        when(mockTool.executePs("echo 'hello world'")).thenReturn(response);

        ConnectionResult result = connection.execCommand("echo 'hello world'", BecomeContext.empty(), null);

        assertNotNull(result);
        assertEquals(0, result.exitCode());
        assertEquals("hello world", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void testExecCommandWithEnvironment() {
        WinRmToolResponse response = new WinRmToolResponse("env_value", "", 0);
        when(mockTool.executePs(anyString())).thenReturn(response);

        Map<String, String> env = Map.of("MY_VAR", "my\"value`abc");
        ConnectionResult result = connection.execCommand("Write-Output $env:MY_VAR", BecomeContext.empty(), env);

        assertNotNull(result);
        assertEquals(0, result.exitCode());
        assertEquals("env_value", result.stdout());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockTool).executePs(captor.capture());
        String executedCommand = captor.getValue();

        assertTrue(executedCommand.contains("$env:MY_VAR = \"my`\"value``abc\";"));
        assertTrue(executedCommand.contains("Write-Output $env:MY_VAR"));
    }

    @Test
    void testExecCommandWithBecomeRunAs() {
        WinRmToolResponse response = new WinRmToolResponse("admin-user", "", 0);
        when(mockTool.executePs(anyString())).thenReturn(response);

        BecomeContext becomeContext = new BecomeContext(true, "runas", "Administrator", "", "adminPass");
        ConnectionResult result = connection.execCommand("whoami", becomeContext, null);

        assertNotNull(result);
        assertEquals(0, result.exitCode());

        // Verification of rebuild tool calls:
        // First tool created for initial/default context (1 call),
        // and second tool created dynamically for become context (1 call).
        assertEquals(2, buildToolCalls.size());
        assertEquals("testuser", buildToolCalls.get(0).user);
        assertEquals("testpassword", buildToolCalls.get(0).password);
        assertEquals("Administrator", buildToolCalls.get(1).user);
        assertEquals("adminPass", buildToolCalls.get(1).password);
    }

    @Test
    void testPutFile(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test.txt");
        // Create a local file with some content (longer than chunk length if we want to test chunking, but any content is fine)
        byte[] contentBytes = "This is some test content to transfer via WinRM Base64 chunking.".getBytes(StandardCharsets.UTF_8);
        Files.write(localFile, contentBytes);

        WinRmToolResponse okResponse = new WinRmToolResponse("", "", 0);
        when(mockTool.executePs(anyString())).thenReturn(okResponse);

        assertDoesNotThrow(() -> connection.putFile(localFile, "C:\\remote\\path\\test.txt"));

        // Capture command arguments to verify chunk and decode commands
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mockTool, atLeastOnce()).executePs(captor.capture());

        List<String> executedCommands = captor.getAllValues();
        // 1. Clear command
        assertTrue(executedCommands.get(0).contains("Join-Path $env:TEMP"));
        assertTrue(executedCommands.get(0).contains("Remove-Item -Path $tempFile -Force"));

        // 2. Append command
        assertTrue(executedCommands.get(1).contains("Join-Path $env:TEMP"));
        assertTrue(executedCommands.get(1).contains("[System.IO.File]::AppendAllText($tempFile,"));

        // 3. Decode command
        assertTrue(executedCommands.get(2).contains("Join-Path $env:TEMP"));
        assertTrue(executedCommands.get(2).contains("Get-Content -Raw -Path $tempFile"));
        assertTrue(executedCommands.get(2).contains("[System.Convert]::FromBase64String($base64)"));
        assertTrue(executedCommands.get(2).contains("C:\\remote\\path\\test.txt"));
    }

    @Test
    void testFetchFile(@TempDir Path tempDir) throws IOException {
        Path localDest = tempDir.resolve("test-dest.txt");

        byte[] remoteContentBytes = "Remote file retrieved via Base64".getBytes(StandardCharsets.UTF_8);
        String remoteBase64 = Base64.getEncoder().encodeToString(remoteContentBytes);

        WinRmToolResponse response = new WinRmToolResponse(remoteBase64, "", 0);
        when(mockTool.executePs(anyString())).thenReturn(response);

        assertDoesNotThrow(() -> connection.fetchFile("C:\\remote\\source.txt", localDest));

        assertTrue(Files.exists(localDest));
        assertArrayEquals(remoteContentBytes, Files.readAllBytes(localDest));

        verify(mockTool).executePs(contains("[System.Convert]::ToBase64String"));
    }

    @Test
    void testClose() {
        connection.close();
        // Since testable connection uses the mocked buildTool, context may not be instantiated,
        // but close should run without null pointer exceptions.
        assertDoesNotThrow(() -> connection.close());
    }

    @Test
    void testPutFilePrepareFailure(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test-fail.txt");
        Files.write(localFile, "some content".getBytes(StandardCharsets.UTF_8));

        WinRmToolResponse failResponse = new WinRmToolResponse("", "mock error preparing temp file", 1);
        when(mockTool.executePs(anyString())).thenReturn(failResponse);

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.putFile(localFile, "C:\\remote\\path\\test-fail.txt")
        );
        assertTrue(ex.getMessage().contains("Failed to prepare remote temp file"));
    }

    @Test
    void testPutFileChunkFailure(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test-fail-chunk.txt");
        Files.write(localFile, "some content".getBytes(StandardCharsets.UTF_8));

        WinRmToolResponse okResponse = new WinRmToolResponse("", "", 0);
        WinRmToolResponse failResponse = new WinRmToolResponse("", "mock error uploading chunk", 1);

        // First call (clear/prepare) succeeds, second call (append chunk) fails
        when(mockTool.executePs(anyString()))
                .thenReturn(okResponse)
                .thenReturn(failResponse);

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.putFile(localFile, "C:\\remote\\path\\test-fail-chunk.txt")
        );
        assertTrue(ex.getMessage().contains("Failed to upload file chunk"));
    }

    @Test
    void testPutFileDecodeFailure(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test-fail-decode.txt");
        Files.write(localFile, "some content".getBytes(StandardCharsets.UTF_8));

        WinRmToolResponse okResponse = new WinRmToolResponse("", "", 0);
        WinRmToolResponse failResponse = new WinRmToolResponse("", "mock error decoding file", 1);

        // First call (clear/prepare) succeeds, second call (append chunk) succeeds, third call (decode) fails
        when(mockTool.executePs(anyString()))
                .thenReturn(okResponse)
                .thenReturn(okResponse)
                .thenReturn(failResponse);

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.putFile(localFile, "C:\\remote\\path\\test-fail-decode.txt")
        );
        assertTrue(ex.getMessage().contains("Failed to decode remote file"));
    }

    @Test
    void testPutFileException(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test-exc.txt");
        Files.write(localFile, "some content".getBytes(StandardCharsets.UTF_8));

        when(mockTool.executePs(anyString())).thenThrow(new RuntimeException("Connection timed out"));

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.putFile(localFile, "C:\\remote\\path\\test-exc.txt")
        );
        assertTrue(ex.getMessage().contains("Failed to upload file to remote path"));
        assertTrue(ex.getMessage().contains("Connection timed out"));
    }

    @Test
    void testFetchFileFailure(@TempDir Path tempDir) {
        Path localDest = tempDir.resolve("test-fail-fetch.txt");

        WinRmToolResponse failResponse = new WinRmToolResponse("", "mock fetch failure", 1);
        when(mockTool.executePs(anyString())).thenReturn(failResponse);

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.fetchFile("C:\\remote\\source.txt", localDest)
        );
        assertTrue(ex.getMessage().contains("Failed to fetch remote file"));
    }

    @Test
    void testFetchFileException(@TempDir Path tempDir) {
        Path localDest = tempDir.resolve("test-fail-fetch-exc.txt");

        when(mockTool.executePs(anyString())).thenThrow(new RuntimeException("Connection reset"));

        UnreachableException ex = assertThrows(UnreachableException.class, () ->
            connection.fetchFile("C:\\remote\\source.txt", localDest)
        );
        assertTrue(ex.getMessage().contains("Failed to download file from remote path"));
        assertTrue(ex.getMessage().contains("Connection reset"));
    }
}
