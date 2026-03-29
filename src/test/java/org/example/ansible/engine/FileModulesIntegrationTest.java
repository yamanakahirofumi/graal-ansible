package org.example.ansible.engine;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class FileModulesIntegrationTest {

    private TaskExecutor taskExecutor;
    private VariableManager variableManager;
    private Play play;
    private Host host;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        host = new Host("localhost", Collections.emptyMap());
        Inventory inventory = new Inventory(new Group("all", List.of(host), List.of(), Map.of()));
        variableManager = new VariableManager(inventory, Map.of(), tempDir);
        play = new Play("Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testFileModule() throws IOException {
        Path targetFile = tempDir.resolve("file-test.txt");

        // 1. touch
        Task taskTouch = new Task("Touch file", "file", Map.of(
                "path", targetFile.toString(),
                "state", "touch"
        ));
        TaskResult resultTouch = taskExecutor.execute(play, host, taskTouch, variableManager, false, null, new LocalConnection(), null);

        if (!resultTouch.success()) {
            System.err.println("Touch failed: " + resultTouch.message());
            System.err.println("Data: " + resultTouch.data());
        }
        assertTrue(resultTouch.success(), resultTouch.message());
        assertTrue(Files.exists(targetFile));

        // 2. directory
        Path targetDir = tempDir.resolve("test-dir");
        Task taskDir = new Task("Create directory", "file", Map.of(
                "path", targetDir.toString(),
                "state", "directory"
        ));
        TaskResult resultDir = taskExecutor.execute(play, host, taskDir, variableManager, false, null, new LocalConnection(), null);

        if (!resultDir.success()) {
            System.err.println("Directory creation failed: " + resultDir.message());
            System.err.println("Data: " + resultDir.data());
        }
        assertTrue(resultDir.success(), "Directory creation failed: " + resultDir.message() + ". Data: " + resultDir.data());
        assertTrue(Files.isDirectory(targetDir), "Target directory was not created: " + targetDir);

        // 3. absent
        Task taskAbsent = new Task("Remove file", "file", Map.of(
                "path", targetFile.toString(),
                "state", "absent"
        ));
        TaskResult resultAbsent = taskExecutor.execute(play, host, taskAbsent, variableManager, false, null, new LocalConnection(), null);

        assertTrue(resultAbsent.success());
        assertFalse(Files.exists(targetFile));
    }

    @Test
    void testStatModule() throws IOException {
        Path targetFile = tempDir.resolve("stat-test.txt");
        Files.writeString(targetFile, "stat data");

        Task task = new Task("Stat file", "stat", Map.of(
                "path", targetFile.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Stat failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        Map<String, Object> stat = (Map<String, Object>) result.data().get("stat");
        assertNotNull(stat, "stat data should not be null. Full data: " + result.data());
        assertTrue((Boolean) stat.get("exists"));
    }

    @Test
    void testTempfileModule() {
        Task task = new Task("Create tempfile", "tempfile", Map.of(
                "state", "file",
                "suffix", "test"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        String path = (String) result.data().get("path");
        assertNotNull(path);
        assertTrue(path.endsWith("test"));
        assertTrue(Files.exists(Path.of(path)));

        // Cleanup
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException ignored) {}
    }

    @Test
    void testFindModule() throws IOException {
        Path f1 = tempDir.resolve("find-1.txt");
        Files.writeString(f1, "data");
        Path f2 = tempDir.resolve("find-2.txt");
        Files.writeString(f2, "data");
        Path f3 = tempDir.resolve("find-3.log");
        Files.writeString(f3, "data");

        // Using a list for paths to test the type conversion fix
        Task task = new Task("Find files", "find", Map.of(
                "paths", List.of(tempDir.toAbsolutePath().toString()),
                "patterns", List.of("find-*.txt")
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Find failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.data().get("files");
        assertNotNull(files, "files should not be null. Full data: " + result.data());

        assertEquals(2, files.size(), "Should find 2 files. Found: " + files.size() + ". Data: " + result.data());
    }

    @Test
    void testSlurpModule() throws IOException {
        Path targetFile = tempDir.resolve("slurp-test.txt");
        String content = "slurp data";
        Files.writeString(targetFile, content);

        Task task = new Task("Slurp file", "slurp", Map.of(
                "src", targetFile.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Slurp failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        String encoded = (String) result.data().get("content");
        assertNotNull(encoded, "content should not be null. Full data: " + result.data());

        // Remove all whitespace including newlines
        String cleanEncoded = encoded.replaceAll("\\s", "");
        try {
            String decoded = new String(Base64.getDecoder().decode(cleanEncoded));
            assertEquals(content, decoded.trim());
        } catch (IllegalArgumentException e) {
            fail("Failed to decode base64: '" + cleanEncoded + "'. Original: '" + encoded + "'. Error: " + e.getMessage());
        }
    }

    @Test
    void testLineInFileModule() throws IOException {
        Path targetFile = tempDir.resolve("line-test.txt");
        Files.writeString(targetFile, "line 1\nline 3\n");

        Task task = new Task("Add line", "lineinfile", Map.of(
                "path", targetFile.toString(),
                "line", "line 2",
                "insertafter", "line 1"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertTrue(content.contains("line 2"));
    }

    @Test
    void testReplaceModule() throws IOException {
        Path targetFile = tempDir.resolve("replace-test.txt");
        Files.writeString(targetFile, "hello world");

        Task task = new Task("Replace text", "replace", Map.of(
                "path", targetFile.toString(),
                "regexp", "world",
                "replace", "ansible"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertEquals("hello ansible", content.trim());
    }

    @Test
    void testBlockInFileModule() throws IOException {
        Path targetFile = tempDir.resolve("block-test.txt");
        Files.writeString(targetFile, "line 1\n");

        Task task = new Task("Add block", "blockinfile", Map.of(
                "path", targetFile.toString(),
                "block", "line 2\nline 3"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertTrue(content.contains("BEGIN ANSIBLE MANAGED BLOCK"));
        assertTrue(content.contains("line 2"));
        assertTrue(content.contains("line 3"));
    }

    @Test
    void testGetentModule() {
        Task task = new Task("Getent passwd", "getent", Map.of(
                "database", "passwd",
                "key", "root"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Getent failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present");
        Map<String, Object> getent = (Map<String, Object>) facts.get("getent_passwd");
        assertNotNull(getent, "getent_passwd should be present in ansible_facts");
        assertTrue(getent.containsKey("root"));
    }

    @Test
    void testFetchModule() throws IOException {
        Path remoteFile = tempDir.resolve("remote-source.txt");
        String content = "remote data to fetch";
        Files.writeString(remoteFile, content);

        Path localDest = tempDir.resolve("local-dest.txt");

        Task task = new Task("Fetch file", "fetch", Map.of(
                "src", remoteFile.toString(),
                "dest", localDest.toString(),
                "flat", true
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Fetch failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertTrue(Files.exists(localDest));
        assertEquals(content, Files.readString(localDest).trim());
    }
}
