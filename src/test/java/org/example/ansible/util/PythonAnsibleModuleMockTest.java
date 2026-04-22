package org.example.ansible.util;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PythonAnsibleModuleMockTest {

    @Mock
    private Connection connection;

    @Mock
    private BecomeContext becomeContext;

    @Mock
    private PythonOSMock osMock;

    @Mock
    private Value pythonPrint;

    @Mock
    private Value pythonExit;

    @TempDir
    Path tempDir;

    private Map<String, String> environment = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Default behavior for osMock.normalizePath
        lenient().when(osMock.normalizePath(any())).thenAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            return arg == null ? null : arg.toString();
        });
    }


    @Test
    void testBasicStructure() {
        assertNotNull(connection);
    }

    @Test
    void testConstructorParamParsing() {
        Map<String, Object> argumentSpec = new HashMap<>();
        argumentSpec.put("str_param", Map.of("type", "str", "default", "default_val"));
        argumentSpec.put("int_param", Map.of("type", "int"));
        argumentSpec.put("bool_param", Map.of("type", "bool"));
        argumentSpec.put("list_param", Map.of("type", "list"));
        argumentSpec.put("path_param", Map.of("type", "path", "aliases", List.of("path_alias")));

        Map<String, Object> inputArgs = new HashMap<>();
        inputArgs.put("str_param", "provided_val");
        inputArgs.put("int_param", "123");
        inputArgs.put("bool_param", "yes");
        inputArgs.put("list_param", "single_item");
        inputArgs.put("path_alias", "/test/path");

        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                argumentSpec, inputArgs, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        Map<String, Object> params = mock.getParams();
        assertEquals("provided_val", params.get("str_param"));
        assertEquals(123, params.get("int_param"));
        assertEquals(true, params.get("bool_param"));
        assertEquals(List.of("single_item"), params.get("list_param"));
        assertEquals("/test/path", params.get("path_param"));
        // Aliases should also be present in params
        assertEquals("/test/path", params.get("path_alias"));
    }

    @Test
    void testConstructorAddFileCommonArgs() {
        Map<String, Object> kwargs = Map.of("add_file_common_args", true);
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, kwargs, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        Map<String, Object> params = mock.getParams();
        assertTrue(params.containsKey("path"));
        assertTrue(params.containsKey("mode"));
        assertTrue(params.containsKey("owner"));
        assertTrue(params.containsKey("group"));
    }

    @Test
    void testFlagInitialization() {
        Map<String, Object> inputArgs = new HashMap<>();
        inputArgs.put("_ansible_check_mode", true);
        inputArgs.put("_ansible_debug", "yes");
        inputArgs.put("_ansible_diff", 1);

        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, inputArgs, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        assertTrue(mock.getCheck_mode());
        assertTrue(mock.get_debug());
        assertTrue(mock.get_diff());
    }

    @Test
    void testExitJson() {
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        mock.exit_json(Map.of("changed", true, "msg", "Success"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(pythonPrint).execute(captor.capture());
        String json = captor.getValue();
        assertTrue(json.contains("\"changed\":true"));
        assertTrue(json.contains("\"msg\":\"Success\""));
        verify(pythonExit).execute(0);
    }

    @Test
    void testFailJson() {
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        mock.fail_json(Map.of("msg", "Error occurred"));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(pythonPrint).execute(captor.capture());
        String json = captor.getValue();
        assertTrue(json.contains("\"failed\":true"));
        assertTrue(json.contains("\"msg\":\"Error occurred\""));
        verify(pythonExit).execute(1);
    }

    @Test
    void testRunCommand() {
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        when(connection.execCommand(anyString(), any(), any())).thenReturn(new ConnectionResult("stdout", "stderr", 0));

        Object[] result = mock.run_command("ls -l");
        assertEquals(0, result[0]);
        assertEquals("stdout", result[1]);
        assertEquals("stderr", result[2]);

        verify(connection).execCommand(eq("ls -l"), eq(becomeContext), eq(environment));
    }

    @Test
    void testGetentMock() {
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        // getent passwd root
        Object[] res1 = mock.run_command(List.of("getent", "passwd", "root"));
        assertEquals(0, res1[0]);
        assertTrue(res1[1].toString().contains("root:x:0:0:root:/root:/bin/bash"));

        // getent group root
        Object[] res2 = mock.run_command(List.of("getent", "group", "root"));
        assertEquals(0, res2[0]);
        assertTrue(res2[1].toString().contains("root:x:0:"));
    }

    @Test
    void testHashing() throws IOException {
        Path testFile = tempDir.resolve("test_hash.txt");
        Files.writeString(testFile, "hello world");

        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        String sha1 = mock.sha1(testFile.toString());
        assertEquals("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed", sha1);

        String md5 = mock.md5(testFile.toString());
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", md5);

        String sha256 = mock.sha256(testFile.toString());
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", sha256);

        String digest = mock.digest_from_file(testFile.toString(), "sha256");
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", digest);
    }

    @Test
    void testFileUtilities() throws IOException {
        PythonAnsibleModuleMock mock = new PythonAnsibleModuleMock(
                null, null, null, osMock, connection, becomeContext, environment, pythonPrint, pythonExit
        );

        // test get_file_attributes
        when(osMock.exists(any())).thenReturn(true);
        when(osMock.stat(any())).thenReturn(new PythonOSMock.StatResult(0100644, 1234, 0, 0, 0));

        Map<String, Object> attrs = mock.get_file_attributes("/some/path");
        assertEquals("644", attrs.get("mode"));
        assertEquals(1234L, attrs.get("size"));

        // test load_file_common_arguments
        Map<String, Object> commonArgs = mock.load_file_common_arguments(
                Map.of("mode", "0644", "owner", "root", "other", "ignore"), "/target/path"
        );
        assertEquals("0644", commonArgs.get("mode"));
        assertEquals("root", commonArgs.get("owner"));
        assertEquals("/target/path", commonArgs.get("path"));
        assertFalse(commonArgs.containsKey("other"));

        // test makedirs_safe
        mock.makedirs_safe("/new/dir", 0755);
        verify(osMock).makedirs("/new/dir", 0755, true);
    }
}
