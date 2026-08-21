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
        TaskResult resultTouch = taskExecutor.execute(play, host, taskTouch, variableManager, false, null, null, new LocalConnection(), null);

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
        TaskResult resultDir = taskExecutor.execute(play, host, taskDir, variableManager, false, null, null, new LocalConnection(), null);

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
        TaskResult resultAbsent = taskExecutor.execute(play, host, taskAbsent, variableManager, false, null, null, new LocalConnection(), null);

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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

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
    void testTempfileModuleVariations() {
        // Directory state execution with prefix and suffix
        Task taskDir = new Task("Create temp directory", "tempfile", Map.of(
                "state", "directory",
                "prefix", "testdir_",
                "suffix", "_dir"
        ));
        TaskResult resultDir = taskExecutor.execute(play, host, taskDir, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultDir.success(), resultDir.message());
        assertTrue(resultDir.changed());
        String dirPath = (String) resultDir.data().get("path");
        assertNotNull(dirPath);
        assertTrue(dirPath.contains("testdir_"));
        assertTrue(dirPath.endsWith("_dir"));
        Path pathObj = Path.of(dirPath);
        assertTrue(Files.exists(pathObj));
        assertTrue(Files.isDirectory(pathObj));

        // Cleanup
        try {
            Files.deleteIfExists(pathObj);
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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

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
    void testFindModuleVariationsAndCheckMode() throws IOException {
        Path subDir = tempDir.resolve("subfolder");
        Files.createDirectory(subDir);
        Path nestedFile = subDir.resolve("find-nested.txt");
        Files.writeString(nestedFile, "nested data");

        // 1. Recurse and file_type: directory in check mode
        Task taskCheckDir = new Task("Find directories in check mode", "find", Map.of(
                "paths", List.of(tempDir.toAbsolutePath().toString()),
                "file_type", "directory",
                "recurse", true
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: true

        TaskResult resultCheckDir = taskExecutor.execute(play, host, taskCheckDir, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultCheckDir.success(), resultCheckDir.message());
        List<Map<String, Object>> dirFiles = (List<Map<String, Object>>) resultCheckDir.data().get("files");
        assertNotNull(dirFiles);
        assertTrue(dirFiles.stream().anyMatch(f -> f.get("path").toString().endsWith("subfolder")));

        // 2. Recurse with pattern matching for nested file
        Task taskRecurseFile = new Task("Find nested file with recurse", "find", Map.of(
                "paths", List.of(tempDir.toAbsolutePath().toString()),
                "patterns", List.of("find-nested.txt"),
                "recurse", true
        ));
        TaskResult resultRecurseFile = taskExecutor.execute(play, host, taskRecurseFile, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultRecurseFile.success(), resultRecurseFile.message());
        List<Map<String, Object>> nestedFiles = (List<Map<String, Object>>) resultRecurseFile.data().get("files");
        assertNotNull(nestedFiles);
        assertEquals(1, nestedFiles.size());
    }

    @Test
    void testSlurpModule() throws IOException {
        Path targetFile = tempDir.resolve("slurp-test.txt");
        String content = "slurp data";
        Files.writeString(targetFile, content);

        Task task = new Task("Slurp file", "slurp", Map.of(
                "src", targetFile.toString()
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertTrue(content.contains("line 2"));
    }

    @Test
    void testLineInFileCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("line-check-test.txt");
        Files.writeString(targetFile, "line 1\nline 3\n");

        Task taskCheck = new Task("Add line check mode", "lineinfile", Map.of(
                "path", targetFile.toString(),
                "line", "line 2",
                "insertafter", "line 1"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult resultCheck = taskExecutor.execute(play, host, taskCheck, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(resultCheck.success(), resultCheck.message());
        assertTrue(resultCheck.changed(), "Check mode should report changed = true");

        String contentCheck = Files.readString(targetFile);
        assertFalse(contentCheck.contains("line 2"), "Target file should not be modified in check mode");

        Task taskRun = new Task("Add line run mode", "lineinfile", Map.of(
                "path", targetFile.toString(),
                "line", "line 2",
                "insertafter", "line 1"
        ));
        TaskResult resultRun = taskExecutor.execute(play, host, taskRun, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(resultRun.success(), resultRun.message());
        assertTrue(resultRun.changed());
        String contentRun = Files.readString(targetFile);
        assertTrue(contentRun.contains("line 2"), "Target file should be modified when check mode is false");
    }

    @Test
    void testLineInFileStateAbsent() throws IOException {
        Path targetFile = tempDir.resolve("line-absent-test.txt");
        Files.writeString(targetFile, "alpha\nbeta\ngamma\n");

        Task task = new Task("Remove line", "lineinfile", Map.of(
                "path", targetFile.toString(),
                "regexp", "beta",
                "state", "absent"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertFalse(content.contains("beta"));
        assertTrue(content.contains("alpha"));
        assertTrue(content.contains("gamma"));
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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertEquals("hello ansible", content.trim());
    }

    @Test
    void testReplaceCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("replace-check-test.txt");
        Files.writeString(targetFile, "foo bar baz");

        Task taskCheck = new Task("Replace text in check mode", "replace", Map.of(
                "path", targetFile.toString(),
                "regexp", "bar",
                "replace", "qux"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);

        TaskResult resultCheck = taskExecutor.execute(play, host, taskCheck, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(resultCheck.success(), resultCheck.message());
        assertTrue(resultCheck.changed(), "Check mode should report changed = true");

        String contentCheck = Files.readString(targetFile);
        assertEquals("foo bar baz", contentCheck.trim(), "Target file should not be modified in check mode");
    }

    @Test
    void testBlockInFileModule() throws IOException {
        Path targetFile = tempDir.resolve("block-test.txt");
        Files.writeString(targetFile, "line 1\n");

        Task task = new Task("Add block", "blockinfile", Map.of(
                "path", targetFile.toString(),
                "block", "line 2\nline 3"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        assertTrue(result.success(), result.message());
        assertTrue(result.changed());

        String content = Files.readString(targetFile);
        assertTrue(content.contains("BEGIN ANSIBLE MANAGED BLOCK"));
        assertTrue(content.contains("line 2"));
        assertTrue(content.contains("line 3"));
    }

    @Test
    void testBlockInFileModuleVariationsAndCheckMode() throws IOException {
        Path targetFile = tempDir.resolve("block-var-test.txt");
        Files.writeString(targetFile, "header line\nfooter line\n");

        // 1. Check mode execution
        Task taskCheck = new Task("Add block in check mode", "blockinfile", Map.of(
                "path", targetFile.toString(),
                "block", "custom content block",
                "marker", "# {mark} CUSTOM BLOCK"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: true

        TaskResult resultCheck = taskExecutor.execute(play, host, taskCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultCheck.success(), resultCheck.message());
        assertTrue(resultCheck.changed(), "Check mode should report changed = true");
        String contentCheck = Files.readString(targetFile);
        assertFalse(contentCheck.contains("CUSTOM BLOCK"), "File should not be modified in check mode");

        // 2. Add block with custom marker and backup
        Task taskAddBlock = new Task("Add block with custom marker and backup", "blockinfile", Map.of(
                "path", targetFile.toString(),
                "block", "custom content block",
                "marker", "# {mark} CUSTOM BLOCK",
                "insertafter", "header line",
                "backup", true
        ));
        TaskResult resultAddBlock = taskExecutor.execute(play, host, taskAddBlock, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultAddBlock.success(), resultAddBlock.message());
        assertTrue(resultAddBlock.changed());
        String contentAdded = Files.readString(targetFile);
        assertTrue(contentAdded.contains("# BEGIN CUSTOM BLOCK"));
        assertTrue(contentAdded.contains("custom content block"));
        assertTrue(resultAddBlock.data().containsKey("backup_file") || resultAddBlock.data().containsKey("backup"),
                "Backup information should be included in result data when backup=true");

        // 3. Remove block with state: absent
        Task taskRemoveBlock = new Task("Remove block with state absent", "blockinfile", Map.of(
                "path", targetFile.toString(),
                "marker", "# {mark} CUSTOM BLOCK",
                "state", "absent"
        ));
        TaskResult resultRemoveBlock = taskExecutor.execute(play, host, taskRemoveBlock, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultRemoveBlock.success(), resultRemoveBlock.message());
        assertTrue(resultRemoveBlock.changed());
        String contentRemoved = Files.readString(targetFile);
        assertFalse(contentRemoved.contains("custom content block"));
        assertFalse(contentRemoved.contains("CUSTOM BLOCK"));
    }

    @Test
    void testGetentModule() {
        // Skip getent test on Windows because the getent utility is typically missing
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }

        Task task = new Task("Getent passwd", "getent", Map.of(
                "database", "passwd",
                "key", "root"
        ));
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Getent failed: " + result.message());
            System.err.println("Full Data: " + result.data());
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
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("Fetch failed: " + result.message());
            System.err.println("Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        assertTrue(result.changed());
        assertTrue(Files.exists(localDest));
        assertEquals(content, Files.readString(localDest).trim());
    }

    @Test
    void testMountFactsModule() {
        Task task = new Task("Get mount facts", "mount_facts", Map.of());
        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("mount_facts failed: " + result.message());
            System.err.println("Full Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        Map<String, Object> facts = (Map<String, Object>) result.data().get("ansible_facts");
        assertNotNull(facts, "ansible_facts should be present");
        // mount_facts returns mount_points and aggregate_mounts
        assertTrue(facts.containsKey("mount_points") || facts.containsKey("mounts"), "mount info should be present in ansible_facts");
    }

    @Test
    void testDpkgSelectionsModule() {
        // dpkg_selections is for setting selections, let's use it in check_mode
        Task task = new Task("Set dpkg selections", "dpkg_selections", Map.of(
                "name", "sed",
                "selection", "install"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null); // check_mode: true

        TaskResult result = taskExecutor.execute(play, host, task, variableManager, false, null, null, new LocalConnection(), null);

        if (!result.success()) {
            System.err.println("dpkg_selections failed: " + result.message());
            System.err.println("Full Data: " + result.data());
        }
        assertTrue(result.success(), result.message());
        assertNotNull(result.data().get("before"));
        assertNotNull(result.data().get("after"));
    }

    @Test
    void testLineInFileCreateAndBackrefsAndBackup() throws IOException {
        Path missingFile = tempDir.resolve("missing-line.txt");

        // 1. test lineinfile with create: true
        Task taskCreate = new Task("Create lineinfile", "lineinfile", Map.of(
                "path", missingFile.toString(),
                "line", "CREATED_LINE=1",
                "create", true
        ));
        TaskResult resultCreate = taskExecutor.execute(play, host, taskCreate, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultCreate.success(), resultCreate.message());
        assertTrue(resultCreate.changed());
        assertTrue(Files.exists(missingFile));
        assertTrue(Files.readString(missingFile).contains("CREATED_LINE=1"));

        // 2. test lineinfile with backrefs and backup
        Task taskBackrefs = new Task("Update with backrefs and backup", "lineinfile", Map.of(
                "path", missingFile.toString(),
                "regexp", "CREATED_LINE=(.*)",
                "line", "UPDATED_LINE=\\1",
                "backrefs", true,
                "backup", true
        ));
        TaskResult resultBackrefs = taskExecutor.execute(play, host, taskBackrefs, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultBackrefs.success(), resultBackrefs.message());
        assertTrue(resultBackrefs.changed());
        assertTrue(Files.readString(missingFile).contains("UPDATED_LINE=1"));
        assertTrue(resultBackrefs.data().containsKey("backup_file") || resultBackrefs.data().containsKey("backup"),
                "backup_file or backup should be returned in data when backup=true. Data: " + resultBackrefs.data());
    }

    @Test
    void testFileModuleCheckMode() throws IOException {
        Path checkFile = tempDir.resolve("file-check.txt");

        // 1. touch check mode
        Task taskTouchCheck = new Task("Touch file in check mode", "file", Map.of(
                "path", checkFile.toString(),
                "state", "touch"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);
        TaskResult resultTouchCheck = taskExecutor.execute(play, host, taskTouchCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultTouchCheck.success(), resultTouchCheck.message());
        assertTrue(resultTouchCheck.changed());
        assertFalse(Files.exists(checkFile), "File should not be created in check mode");

        // 2. directory check mode
        Path checkDir = tempDir.resolve("check-dir");
        Task taskDirCheck = new Task("Create directory in check mode", "file", Map.of(
                "path", checkDir.toString(),
                "state", "directory"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);
        TaskResult resultDirCheck = taskExecutor.execute(play, host, taskDirCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultDirCheck.success(), resultDirCheck.message());
        assertTrue(resultDirCheck.changed());
        assertFalse(Files.exists(checkDir), "Directory should not be created in check mode");

        // 3. absent check mode on existing file
        Files.writeString(checkFile, "exist");
        Task taskAbsentCheck = new Task("Remove file in check mode", "file", Map.of(
                "path", checkFile.toString(),
                "state", "absent"
        ), Map.of(), null, null, null, List.of(), null, null, false,
                null, 3, 5, null, false, false, false, List.of(), List.of(), List.of(),
                null, null, null, null, true, null);
        TaskResult resultAbsentCheck = taskExecutor.execute(play, host, taskAbsentCheck, variableManager, false, null, null, new LocalConnection(), null);
        assertTrue(resultAbsentCheck.success(), resultAbsentCheck.message());
        assertTrue(resultAbsentCheck.changed());
        assertTrue(Files.exists(checkFile), "File should not be deleted in check mode");
    }
}
