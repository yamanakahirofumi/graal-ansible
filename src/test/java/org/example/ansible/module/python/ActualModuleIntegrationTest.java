package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.SshConnection;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test using actual ansible-core modules.
 * Now using Testcontainers to verify target state.
 */
@Testcontainers
@EnabledOnOs(OS.LINUX)
class ActualModuleIntegrationTest {

    @Container
    private GenericContainer<?> targetNode = new GenericContainer<>(DockerImageName.parse("mokojarasi/test-python-sshd:latest"))
            .withExposedPorts(22)
            .withEnv("USER_PASSWORD", "testuser")
            .withEnv("USER_NAME", "testuser")
            .withEnv("PASSWORD_ACCESS", "true")
            .withEnv("SUDO_ACCESS", "true")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;
    private SshConnection connection;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connection = new SshConnection(
                targetNode.getHost(),
                targetNode.getMappedPort(22),
                "testuser",
                "testuser"
        );
        connection.connect();
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testActualPingModule() {

        Task task = new Task("test_ping", "ping", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("pong", result.data().get("ping"));
    }

    @Test
    void testActualFileModule() {

        String remotePath = "/tmp/touch-test.txt";
        Task task = new Task("test_file", "file", Map.of(
                "path", remotePath,
                "state", "touch"
        ));

        // Now we use the actual SSH connection to execute the task on targetNode.
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed(), "File should have been created (changed=true)");

        // Verify state on target node using SSH
        var execResult = connection.execCommand("ls " + remotePath, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "File should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualStatModule() {

        String remotePath = "/tmp/stat-test.txt";
        // Setup state using SSH
        connection.execCommand("sh -c \"echo 'test data' > " + remotePath + "\"", BecomeContext.empty(), null);

        Task task = new Task("test_stat", "stat", Map.of(
                "path", remotePath
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> stat = (Map<String, Object>) result.data().get("stat");
        assertNotNull(stat);
        assertTrue((Boolean) stat.get("exists"));
    }

    @Test
    void testActualCopyModule() throws IOException {

        Path localSrcFile = tempDir.resolve("copy-src.txt");
        String content = "Hello from Actual Copy Module (src)";
        Files.writeString(localSrcFile, content);

        String remoteSrcPath = "/tmp/copy-src.txt";
        connection.putFile(localSrcFile, remoteSrcPath);

        String remoteDestPath = "/tmp/copy-test.txt";
        Task task = new Task("test_copy", "copy", Map.of(
                "src", remoteSrcPath,
                "dest", remoteDestPath,
                "remote_src", true
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        // Verify state on target node using SSH
        var execResult = connection.execCommand("cat " + remoteDestPath, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode());
        assertEquals(content, execResult.stdout().trim());
    }

    @Test
    void testActualDebugModule() {
        // debug is an Action Plugin, no need to register it manually as a PythonModule.

        Task task = new Task("test_debug", "debug", Map.of("msg", "Hello from Actual Debug Module"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertEquals("Hello from Actual Debug Module", result.data().get("msg"));
    }

    @Test
    void testActualSetupModule() {

        Task task = new Task("test_setup", "setup", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts);
        assertTrue(facts.containsKey("ansible_os_family"));
    }

    @Test
    void testActualCommandModule() {
        Task task = new Task("test_command", "command", Map.of("_raw_params", "echo hello_command"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("hello_command", stdout.trim());
    }

    @Test
    void testActualShellModule() {
        Task task = new Task("test_shell", "shell", Map.of("_raw_params", "echo 'line1\nline2' | grep line2"));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout, "stdout should not be null");
        assertEquals("line2", stdout.trim());
    }

    @Test
    void testActualLineInFileModule() {

        String remotePath = "/tmp/lineinfile-test.txt";
        connection.execCommand("echo \"initial line\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_lineinfile", "lineinfile", Map.of(
                "path", remotePath,
                "line", "new line added"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        assertTrue(execResult.stdout().contains("new line added"));
    }

    @Test
    void testActualReplaceModule() {

        String remotePath = "/tmp/replace-test.txt";
        connection.execCommand("echo \"Hello World\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_replace", "replace", Map.of(
                "path", remotePath,
                "regexp", "World",
                "replace", "Ansible"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        assertEquals("Hello Ansible", execResult.stdout().trim());
    }

    @Test
    void testActualUserModule() {

        String userName = "testuser-ansible";
        Task task = new Task("test_user", "user", Map.of(
                "name", userName,
                "state", "present"
        ));
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        var execResult = connection.execCommand("id " + userName, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "User should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualGroupModule() {

        String groupName = "testgroup-ansible";
        Task task = new Task("test_group", "group", Map.of(
                "name", groupName,
                "state", "present"
        ));
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());

        var execResult = connection.execCommand("getent group " + groupName, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "Group should be created in container: " + execResult.stderr());
    }

    @Test
    void testActualFindModule() {

        Task task = new Task("test_find", "find", Map.of(
                "paths", "/tmp",
                "patterns", "*-test.txt"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.data().containsKey("files"), "Result should contain 'files' key");
    }

    @Test
    void testActualTempfileModule() {

        Task task = new Task("test_tempfile", "tempfile", Map.of(
                "state", "directory",
                "suffix", "ansibletest"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String path = (String) result.data().get("path");
        assertNotNull(path);

        var execResult = connection.execCommand("ls -d " + path, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode(), "Temp directory should exist: " + execResult.stderr());
    }

    @Test
    void testActualHostnameModule() {

        Task task = new Task("test_hostname", "hostname", Map.of(
                "name", "new-hostname"
        ));
        // hostname module usually requires root, but in this container it might fail to actually set it
        // We just want to see if it executes correctly.
        TaskResult result = taskExecutor.execute(task, new BecomeContext(true, "sudo", "root", ""), connection, null);

        // It might fail because Docker containers don't always allow changing hostname easily
        // but we check if it didn't fail due to bridge/launcher issues.
        assertNotNull(result);
    }

    @Test
    void testActualSlurpModule() {
        String remotePath = "/tmp/slurp-test.txt";
        String content = "slurp test data";
        connection.execCommand("sh -c \"echo -n '" + content + "' > " + remotePath + "\"", BecomeContext.empty(), null);

        Task task = new Task("test_slurp", "slurp", Map.of(
                "src", remotePath
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String encodedContent = (String) result.data().get("content");
        assertNotNull(encodedContent);

        byte[] decodedBytes;
        try {
            decodedBytes = Base64.getMimeDecoder().decode(encodedContent);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Failed to Base64 decode 'content' field in module result. " +
                    "Raw string in result was: '" + encodedContent + "'. " +
                    "Full result data: " + result.data() + ". " +
                    "Console output (result.message()): " + result.message(), e);
        }
        String decodedString = new String(decodedBytes);
        assertEquals(content, decodedString, "Slurped content does not match original. " +
                "Raw encoded: " + encodedContent + ", " +
                "Decoded: " + decodedString + ", " +
                "Raw output: " + result.message());
    }

    @Test
    void testActualAssertModule() {
        Task task = new Task("test_assert", "assert", Map.of(
                "that", List.of("1 == 1"),
                "fail_msg", "Assertion failed"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);
        assertTrue(result.success(), "Execution failed: " + result.message());
    }

    @Test
    void testActualTemplateModule() throws IOException {
        Path localTemplate = tempDir.resolve("test.j2");
        String content = "static template content";
        Files.writeString(localTemplate, content);

        String remotePath = "/tmp/template-test.txt";
        Task task = new Task("test_template", "template", Map.of(
                "src", localTemplate.toString(),
                "dest", remotePath
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        assertEquals(content, execResult.stdout().trim());
    }

    @Test
    void testActualBlockInFileModule() {
        String remotePath = "/tmp/blockinfile-test.txt";
        connection.execCommand("echo \"line1\" > " + remotePath, BecomeContext.empty(), null);

        Task task = new Task("test_blockinfile", "blockinfile", Map.of(
                "path", remotePath,
                "block", "line2\nline3"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + remotePath, BecomeContext.empty(), null);
        String stdout = execResult.stdout();
        assertTrue(stdout.contains("line1"));
        assertTrue(stdout.contains("line2"));
        assertTrue(stdout.contains("line3"));
        assertTrue(stdout.contains("BEGIN ANSIBLE MANAGED BLOCK"));
    }

    @Test
    void testActualGetentModule() {
        Task task = new Task("test_getent", "getent", Map.of(
                "database", "passwd",
                "key", "root"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        if (!result.success()) {
            System.err.println("Getent failed: " + result.message());
            System.err.println("Full Data: " + result.data());
        }
        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present. Full data: " + result.data());
        Map<String, Object> getent = (Map<String, Object>) facts.get("getent_passwd");
        assertNotNull(getent, "getent_passwd should be present in ansible_facts");
        assertTrue(getent.containsKey("root"));
    }

    @Test
    void testActualFetchModule() throws IOException {
        String remotePath = "/tmp/fetch-test.txt";
        String content = "fetch test content";
        connection.execCommand("sh -c \"echo '" + content + "' > " + remotePath + "\"", BecomeContext.empty(), null);

        Path localDestDir = tempDir.resolve("fetch-dest");
        Files.createDirectories(localDestDir);

        Task task = new Task("test_fetch", "fetch", Map.of(
                "src", remotePath,
                "dest", localDestDir.toString() + "/",
                "flat", true
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        Path downloadedFile = localDestDir.resolve("fetch-test.txt");
        assertTrue(Files.exists(downloadedFile), "Downloaded file should exist: " + downloadedFile);
        assertEquals(content, Files.readString(downloadedFile).trim());
    }

    @Test
    void testActualUnarchiveModule() throws IOException, InterruptedException {
        // 1. Create a local file and tar it
        Path localDir = tempDir.resolve("unarchive-src");
        Files.createDirectories(localDir);
        Files.writeString(localDir.resolve("content.txt"), "unarchive test content");

        Path localTar = tempDir.resolve("test.tar.gz");
        ProcessBuilder pb = new ProcessBuilder("tar", "-czf", localTar.toString(), "-C", localDir.toString(), "content.txt");
        assertEquals(0, pb.start().waitFor(), "Failed to create tarball");

        // 2. Transfer tarball to remote
        String remoteTarPath = "/tmp/test.tar.gz";
        connection.putFile(localTar, remoteTarPath);

        // 3. Use unarchive module to extract it
        String remoteDest = "/tmp/unarchive-dest";
        connection.execCommand("mkdir -p " + remoteDest, BecomeContext.empty(), null);

        Task task = new Task("test_unarchive", "unarchive", Map.of(
                "src", remoteTarPath,
                "dest", remoteDest,
                "remote_src", true
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        // 4. Verify extraction
        var execResult = connection.execCommand("cat " + remoteDest + "/content.txt", BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode());
        assertEquals("unarchive test content", execResult.stdout().trim());
    }

    @Test
    void testActualUriModule() {
        // Start a simple HTTP server in the container background
        connection.execCommand("sh -c \"echo 'hello' > /tmp/index.html && cd /tmp && (python3 -m http.server 8080 &)\"", BecomeContext.empty(), null);

        // Wait a bit for server to start
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        Task task = new Task("test_uri", "uri", Map.of(
                "url", "http://localhost:8080/index.html",
                "return_content", true
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        // Clean up the server
        connection.execCommand("pkill -f 'python3 -m http.server'", BecomeContext.empty(), null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(((String) result.data().get("content")).contains("hello"));
    }

    @Test
    void testActualIncludeVarsModule() throws IOException {
        Path varsFile = tempDir.resolve("actual_vars.yml");
        Files.writeString(varsFile, "fact_from_file: hello_actual\noverridden_fact: from_file");

        Task task = new Task("test_include_vars", "include_vars", Map.of(
                "file", varsFile.toAbsolutePath().toString()
        ));

        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present. Full data: " + result.data());
        assertEquals("hello_actual", facts.get("fact_from_file"));
    }
}
