package org.example.ansible.engine;

import org.example.ansible.connection.LocalConnection;
import org.example.ansible.inventory.Group;
import org.example.ansible.inventory.Host;
import org.example.ansible.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NegativeIntegrationTest {

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
        play = new Play("Negative Test Play", "all", List.of(), Map.of(), List.of(), List.of(), null, null, null, null, null, Map.of());
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
        // Ensure read-only permissions are restored so @TempDir cleanup succeeds
        File dir = tempDir.toFile();
        if (dir.exists()) {
            makeWritableRecursively(dir);
        }
    }

    private void makeWritableRecursively(File file) {
        file.setWritable(true, false);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    makeWritableRecursively(child);
                }
            }
        }
    }

    @Test
    void testMissingRequiredArgument() {
        // 'file' module requires 'path'
        Task task = new Task("Missing path", "file", Map.of(
                "state", "touch"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should have failed due to missing 'path'");
        String msg = result.data().getOrDefault("msg", "").toString();
        assertTrue(msg.contains("missing") || msg.contains("path"),
                "Error message should mention missing parameter or 'path'. Message: " + result.message() + ", Data: " + result.data());
    }

    @Test
    void testInvalidArgumentType() {
        // For 'copy' module, 'dest' must be a string.
        Task task = new Task("Invalid type", "copy", Map.of(
                "dest", List.of("/tmp/invalid1", "/tmp/invalid2"),
                "content", "test"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Task should have failed due to invalid type (multi-element list) for 'dest'");
        String msg = result.data().getOrDefault("msg", "").toString();
        assertTrue(msg.contains("list") || msg.contains("endswith") || msg.contains("PathLike"),
                "Error message should mention type error. Message: " + msg + ", Data: " + result.data());
    }

    @Test
    void testNonExistentFileForSlurp() {
        Path nonExistent = tempDir.resolve("does-not-exist.txt");
        Task task = new Task("Slurp non-existent", "slurp", Map.of(
                "src", nonExistent.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Slurp should have failed for non-existent file");
        assertTrue(result.data().containsKey("msg"));
    }

    @Test
    void testCommandFailure() {
        Task task = new Task("Run invalid command", "command", Map.of(
                "_raw_params", "nonexistentcommand_xyz_123"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Command should have failed");
        assertNotEquals(0, result.data().get("rc"), "Return code should not be zero");
    }

    @Test
    void testInvalidJinjaTemplate() {
        // Jinja syntax error. VariableResolver now throws RuntimeException.
        Task task = new Task("Invalid jinja", "debug", Map.of(
                "msg", "hello {{ name"
        ));
        assertThrows(RuntimeException.class, () ->
                taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null)
        );
    }

    @Test
    void testCopyNonExistentSourceFile() {
        Path nonExistentSrc = tempDir.resolve("non_existent_source.txt");
        Path destFile = tempDir.resolve("dest.txt");

        Task task = new Task("Copy non-existent src", "copy", Map.of(
                "src", nonExistentSrc.toString(),
                "dest", destFile.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Copy should have failed for non-existent source file");
        assertTrue(result.data().containsKey("msg") || result.data().containsKey("exception"),
                "Result data should contain error information: " + result.data());
    }

    @Test
    void testCopyParentDirDoesNotExist() {
        Path nonExistentParent = tempDir.resolve("no_such_dir").resolve("target.txt");

        Task task = new Task("Copy to non-existent directory", "copy", Map.of(
                "content", "sample content",
                "dest", nonExistentParent.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Copy should have failed when target parent directory does not exist");
    }

    @Test
    void testFetchMissingRequiredParams() {
        Task task = new Task("Fetch missing dest", "fetch", Map.of(
                "src", tempDir.resolve("file.txt").toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Fetch should have failed due to missing 'dest' parameter");
    }

    @Test
    void testLineInFileMissingRequiredParams() {
        Task task = new Task("Lineinfile missing line", "lineinfile", Map.of(
                "path", tempDir.resolve("file.txt").toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Lineinfile should have failed due to missing 'line' or 'regexp'");
    }

    @Test
    void testReplaceMissingRequiredParams() {
        Task task = new Task("Replace missing regexp", "replace", Map.of(
                "path", tempDir.resolve("file.txt").toString(),
                "replace", "new_value"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "Replace should have failed due to missing 'regexp'");
    }

    @Test
    void testGetUrlMissingRequiredParams() {
        Task task = new Task("get_url missing url", "get_url", Map.of(
                "dest", tempDir.resolve("downloaded.txt").toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "get_url should have failed due to missing 'url'");
    }

    @Test
    void testUserMissingRequiredParams() {
        Task task = new Task("user missing name", "user", Map.of(
                "state", "present"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "user module should have failed due to missing 'name'");
    }

    @Test
    void testUnarchiveMissingRequiredParams() {
        Task task = new Task("unarchive missing dest", "unarchive", Map.of(
                "src", tempDir.resolve("archive.tar.gz").toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "unarchive module should have failed due to missing 'dest'");
    }

    @Test
    void testGitMissingRequiredParams() {
        Task task = new Task("git missing repo", "git", Map.of(
                "dest", tempDir.resolve("repo_dir").toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertFalse(result.success(), "git module should have failed due to missing 'repo'");
    }

    @Test
    void testFilePermissionDenied() throws IOException {
        Path readOnlyDir = tempDir.resolve("readonly_dir");
        Files.createDirectories(readOnlyDir);
        File dirFile = readOnlyDir.toFile();
        boolean permissionChanged = dirFile.setWritable(false, false);

        if (!permissionChanged) {
            // Skip test if running as root or OS does not enforce setWritable(false)
            return;
        }

        try {
            Path targetFile = readOnlyDir.resolve("test.txt");
            Task task = new Task("Touch file in read-only dir", "file", Map.of(
                    "path", targetFile.toString(),
                    "state", "touch"
            ));
            TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

            assertFalse(result.success(), "Creating file in read-only dir should fail");
        } finally {
            dirFile.setWritable(true, false);
        }
    }
}
