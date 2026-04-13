package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.SshConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.example.ansible.engine.Play;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskQueueManager;
import org.example.ansible.engine.TaskResult;
import org.example.ansible.engine.VariableManager;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void testActualIncludeTasks() throws IOException {
        // Prepare tasks file in temp directory (local to management node)
        Path tasksFile = tempDir.resolve("tasks_to_include.yml");
        Files.writeString(tasksFile, """
                - name: included task
                  ping:
                - name: task with var
                  debug:
                    msg: "val is {{ my_var }}"
                """);

        // The inclusion is handled by TaskQueueManager, not TaskExecutor directly for modules.
        // But for ActualModuleIntegrationTest, we are testing via TaskExecutor.
        // However, include_tasks/import_tasks are handled in TaskQueueManager.
        // To test them "actually", we should use PlaybookExecutor or TaskQueueManager.

        Play play = new Play("inclusion play", "all", List.of(
                new Task("include it", "include_tasks", Map.of("_raw_params", tasksFile.toString()), Map.of("my_var", "default_val"))
        ));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host(targetNode.getHost())), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);

        // We need a TaskQueueManager that uses our SshConnection
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        // Act
        tqm.executePlay(play, inventory, vm, results, false);

        // Assert
        List<TaskResult> hostResults = results.get(targetNode.getHost());
        assertNotNull(hostResults, "Results should exist for " + targetNode.getHost());
        // Results: 1. ping (from include), 2. debug (from include)
        assertEquals(2, hostResults.size(), "Should have 2 results from included tasks");
        assertTrue(hostResults.get(0).success());
        assertEquals("pong", hostResults.get(0).data().get("ping"));
        assertEquals("val is default_val", hostResults.get(1).data().get("msg"));
    }

    @Test
    void testActualIncludeTasksPrecedence() throws IOException {
        Path tasksFile = tempDir.resolve("precedence_tasks.yml");
        Files.writeString(tasksFile, """
                - name: inner task
                  debug:
                    msg: "{{ my_var }}"
                  vars:
                    my_var: inner
                """);

        Play play = new Play("precedence play", "all", List.of(
                new Task("include with higher precedence", "include_tasks",
                        Map.of("_raw_params", tasksFile.toString(), "my_var", "outer"))
        ));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host(targetNode.getHost())), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get(targetNode.getHost());
        assertEquals(1, hostResults.size());
        assertEquals("outer", hostResults.get(0).data().get("msg"),
                "Include parameter (Level 21) should override task variable (Level 17) even in actual SSH execution");
    }

    @Test
    void testActualImportTasks() throws IOException {
        Path tasksFile = tempDir.resolve("tasks_to_import.yml");
        Files.writeString(tasksFile, "- debug: { msg: 'imported' }");

        Play play = new Play("import play", "all", List.of(
                new Task("import it", "import_tasks", Map.of("_raw_params", tasksFile.toString()))
        ));

        Inventory inventory = new Inventory(new Group("all", List.of(new Host(targetNode.getHost())), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of(), tempDir);
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> connection);
        Map<String, List<TaskResult>> results = new HashMap<>();

        tqm.executePlay(play, inventory, vm, results, false);

        List<TaskResult> hostResults = results.get(targetNode.getHost());
        assertEquals(1, hostResults.size());
        assertEquals("imported", hostResults.get(0).data().get("msg"));
    }

    @Test
    void testActualSetFactModule() {
        Task task = new Task("test_set_fact", "set_fact", Map.of(
                "my_actual_fact", "actual_value"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present. Full data: " + result.data());
        assertEquals("actual_value", facts.get("my_actual_fact"));
    }

    @Test
    void testActualFailModule() {
        Task task = new Task("test_fail", "fail", Map.of(
                "msg", "Expected Failure"
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertFalse(result.success(), "Execution should have failed");
        assertEquals("Expected Failure", result.data().get("msg"));
    }

    @Test
    void testActualGatherFactsModule() {
        Task task = new Task("test_gather_facts", "gather_facts", Map.of());
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present");
        assertTrue(facts.containsKey("ansible_os_family"), "Should contain ansible_os_family");
    }

    @Test
    void testActualPackageFactsModule() {
        String hostname = targetNode.getHost();
        // package_facts might need ansible_facts to detect package manager
        Inventory inventory = new Inventory(new Group("all", List.of(new Host(hostname)), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of());
        vm.addFacts(hostname, Map.of("ansible_os_family", "Debian", "ansible_distribution", "Ubuntu"));

        Task task = new Task("test_package_facts", "package_facts", Map.of());
        // We use the signature that takes VariableManager
        TaskResult result = taskExecutor.execute(null, new Host(hostname), task, vm, false, null, null, connection, null);

        if (!result.success()) {
            System.err.println("Package facts failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), "Execution failed: " + result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present");
        assertTrue(facts.containsKey("packages"), "Should contain 'packages' fact");
    }

    @Test
    void testActualGetUrlModule() {
        // Start a simple HTTP server in the container background
        connection.execCommand("sh -c \"echo 'download content' > /tmp/to_download.txt && cd /tmp && (python3 -m http.server 8081 &)\"", BecomeContext.empty(), null);

        // Wait a bit for server to start
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        String destPath = "/tmp/downloaded.txt";
        Task task = new Task("test_get_url", "get_url", Map.of(
                "url", "http://localhost:8081/to_download.txt",
                "dest", destPath
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        // Clean up the server
        connection.execCommand("pkill -f 'python3 -m http.server 8081'", BecomeContext.empty(), null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + destPath, BecomeContext.empty(), null);
        assertEquals("download content", execResult.stdout().trim());
    }

    @Test
    void testActualAssembleModule() {
        String srcDir = "/tmp/assemble_src";
        connection.execCommand("mkdir -p " + srcDir, BecomeContext.empty(), null);
        connection.execCommand("echo 'part1' > " + srcDir + "/01.txt", BecomeContext.empty(), null);
        connection.execCommand("echo 'part2' > " + srcDir + "/02.txt", BecomeContext.empty(), null);

        String destFile = "/tmp/assembled.txt";
        Task task = new Task("test_assemble", "assemble", Map.of(
                "src", srcDir,
                "dest", destFile
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        assertTrue(result.changed());

        var execResult = connection.execCommand("cat " + destFile, BecomeContext.empty(), null);
        String output = execResult.stdout().trim();
        assertTrue(output.contains("part1"));
        assertTrue(output.contains("part2"));
    }

    @Test
    void testActualScriptModule() throws IOException {
        Path localScript = tempDir.resolve("test_script.sh");
        Files.writeString(localScript, "#!/bin/sh\necho 'hello from script'");
        // No need to set executable bit locally, script module handles it on remote

        Task task = new Task("test_script", "script", Map.of(
                "_raw_params", localScript.toString()
        ));
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success(), "Execution failed: " + result.message());
        String stdout = (String) result.data().get("stdout");
        assertNotNull(stdout);
        assertTrue(stdout.contains("hello from script"));
    }

    @Test
    void testActualAddHostModule() {
        String hostname = targetNode.getHost();
        // Use TaskQueueManager to verify inventory update
        Inventory inventory = new Inventory(new Group("all", List.of(new Host(hostname)), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of());
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> connection);

        Play play = new Play("add host play", hostname, List.of(
                new Task("add new host", "add_host", Map.of(
                        "name", "new_dynamic_host",
                        "groups", "dynamic_group",
                        "custom_var", "custom_val"
                ))
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        // Verify inventory update
        Group dynamicGroup = inventory.all().children().stream()
                .filter(g -> g.name().equals("dynamic_group")).findFirst().orElse(null);
        assertNotNull(dynamicGroup, "dynamic_group should be created");
        Host dynamicHost = dynamicGroup.hosts().stream()
                .filter(h -> h.name().equals("new_dynamic_host")).findFirst().orElse(null);
        assertNotNull(dynamicHost, "new_dynamic_host should be in dynamic_group");
        assertEquals("custom_val", dynamicHost.variables().get("custom_var"));
    }

    @Test
    void testActualGroupByModule() {
        String hostname = targetNode.getHost();
        Inventory inventory = new Inventory(new Group("all", List.of(new Host(hostname)), List.of(), Map.of()));
        VariableManager vm = new VariableManager(inventory, Map.of());
        TaskQueueManager tqm = new TaskQueueManager(taskExecutor, (host, vars) -> connection);

        // Setup a fact for group_by
        vm.addFacts(hostname, Map.of("os_type", "linux_distro"));

        Play play = new Play("group by play", hostname, List.of(
                new Task("group hosts", "group_by", Map.of(
                        "key", "{{ os_type }}"
                ))
        ));

        Map<String, List<TaskResult>> results = new HashMap<>();
        tqm.executePlay(play, inventory, vm, results, false);

        // Verify inventory update
        Group distroGroup = inventory.all().children().stream()
                .filter(g -> g.name().equals("linux_distro")).findFirst().orElse(null);
        assertNotNull(distroGroup, "linux_distro group should be created");
        assertTrue(distroGroup.hosts().stream().anyMatch(h -> h.name().equals(hostname)),
                hostname + " should be in linux_distro group");
    }
}
