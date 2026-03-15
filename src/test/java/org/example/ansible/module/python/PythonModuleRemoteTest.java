package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.Connection;
import org.example.ansible.connection.ConnectionResult;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.example.ansible.engine.TaskExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PythonModuleRemoteTest {

    @TempDir
    Path tempDir;

    @Test
    void testExecuteRemotelyTransfersZip() throws IOException {
        // Setup mock connection
        Connection connection = mock(Connection.class);
        when(connection.execCommand(anyString(), any(), any())).thenReturn(new ConnectionResult("{\"failed\": false, \"changed\": true}", "", 0));
        TaskExecutor.setCurrentConnection(connection);

        // Create a dummy ansible installation in tempDir to satisfy PythonEnv
        Path sitePackages = tempDir.resolve("site-packages");
        Path ansibleDir = sitePackages.resolve("ansible");
        Files.createDirectories(ansibleDir.resolve("module_utils"));
        Files.createDirectories(ansibleDir.resolve("_vendor"));
        Files.createDirectories(ansibleDir.resolve("_internal"));
        Files.createDirectories(ansibleDir.resolve("compat"));
        Files.createDirectories(ansibleDir.resolve("modules"));
        Files.writeString(ansibleDir.resolve("__init__.py"), "");
        Files.writeString(ansibleDir.resolve("release.py"), "__version__ = '1.0.0'");
        Files.writeString(ansibleDir.resolve("module_utils/basic.py"), "class AnsibleModule: pass");
        Files.writeString(ansibleDir.resolve("_vendor/__init__.py"), "");
        Files.writeString(ansibleDir.resolve("_internal/__init__.py"), "");
        Files.writeString(ansibleDir.resolve("compat/__init__.py"), "");
        Files.writeString(ansibleDir.resolve("modules/ping.py"), "print('{\"ping\": \"pong\"}')");

        // Set system property for PythonEnv
        System.setProperty("ansible.site.packages", sitePackages.toAbsolutePath().toString());

        final List<String> capturedContents = new ArrayList<>();
        doAnswer((Answer<Void>) invocation -> {
            Path localPath = invocation.getArgument(0);
            if (localPath.getFileName().toString().startsWith("ansible-module-")) {
                capturedContents.add(Files.readString(localPath));
            }
            return null;
        }).when(connection).putFile(any(Path.class), anyString());

        try {
            PythonModule module = new PythonModule("ping");

            TaskResult result = module.execute(Map.of(), BecomeContext.empty(), null); // null context since it's remote

            // Verify zip was transferred
            ArgumentCaptor<String> remotePathCaptor = ArgumentCaptor.forClass(String.class);
            verify(connection, atLeastOnce()).putFile(any(Path.class), remotePathCaptor.capture());

            boolean zipTransferred = false;
            boolean moduleTransferredWithPrefix = false;
            for (String remotePath : remotePathCaptor.getAllValues()) {
                if (remotePath.endsWith("ansible_lib.zip")) {
                    zipTransferred = true;
                }
                if (remotePath.endsWith("/Ansiballz_ping.py")) {
                    moduleTransferredWithPrefix = true;
                }
            }
            assertTrue(zipTransferred, "ansible_lib.zip should have been transferred");
            assertTrue(moduleTransferredWithPrefix, "Module should have been transferred with 'Ansiballz_' prefix");

            // Verify script contains sys.path.insert
            boolean scriptHasSysPath = false;
            boolean scriptHasMonkeyPatch = false;
            boolean scriptHasModuleFqn = false;
            boolean scriptHasProfile = false;
            boolean scriptHasCompileWithPrefix = false;
            for (String content : capturedContents) {
                if (content.contains("sys.path.insert(0, os.path.join(script_dir, 'ansible_lib.zip'))")) {
                    scriptHasSysPath = true;
                }
                if (content.contains("ansible.module_utils.basic._load_params = lambda: (complex_args, 'main')") &&
                    content.contains("def mocked_load_params(self): self.params = complex_args")) {
                    scriptHasMonkeyPatch = true;
                }
                if (content.contains("__main__._module_fqn = 'ansible.builtin.ping'")) {
                    scriptHasModuleFqn = true;
                }
                if (content.contains("ansible.module_utils.basic._ANSIBLE_PROFILE = 'modern'")) {
                    scriptHasProfile = true;
                }
                if (content.contains("exec(compile(module_code, 'Ansiballz_ping.py', 'exec'), globals())")) {
                    scriptHasCompileWithPrefix = true;
                }
            }
            assertTrue(scriptHasSysPath, "Wrapped script should include ansible_lib.zip in sys.path");
            assertTrue(scriptHasMonkeyPatch, "Wrapped script should include monkeypatch for _load_params");
            assertTrue(scriptHasModuleFqn, "Wrapped script should set __main__._module_fqn");
            assertTrue(scriptHasProfile, "Wrapped script should set _ANSIBLE_PROFILE");
            assertTrue(scriptHasCompileWithPrefix, "Wrapped script should use 'Ansiballz_' prefix in compile()");

        } finally {
            System.clearProperty("ansible.site.packages");
            TaskExecutor.clearCurrentConnection();
        }
    }
}
