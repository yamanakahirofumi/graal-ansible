package org.example.ansible.module.python;

import org.example.ansible.connection.BecomeContext;
import org.example.ansible.engine.Task;
import org.example.ansible.engine.TaskExecutor;
import org.example.ansible.engine.TaskResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CopyModuleTest {

    @TempDir
    Path tempDir;

    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (taskExecutor != null) {
            taskExecutor.close();
        }
    }

    @Test
    void testCopyModuleWithContent() throws IOException {
        Path destFile = tempDir.resolve("hello.txt");
        String content = "Hello from GraalPy Ansible!";

        // Mock implementation for testing without actual ansible-core
        String copyModuleMock = 
            "import os\n" +
            "import json\n" +
            "args = complex_args\n" +
            "content = args.get('content')\n" +
            "dest = args.get('dest')\n" +
            "changed = False\n" +
            "if content and dest:\n" +
            "    with open(dest, 'w') as f:\n" +
            "        f.write(content)\n" +
            "    changed = True\n" +
            "print(json.dumps({'changed': changed, 'dest': dest, 'success': True}))\n";

        taskExecutor.registerModule("copy", new PythonModule("copy", copyModuleMock));
        
        Map<String, Object> args = Map.of(
            "content", content,
            "dest", destFile.toString()
        );

        Task task = new Task("test", "copy", args);
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assertTrue(result.success(), "Module execution should be successful: " + result.message());
        assertTrue(result.changed(), "Module should have changed the state");
        assertTrue(Files.exists(destFile), "Destination file should exist");
        assertEquals(content, Files.readString(destFile), "File content should match");
    }

    @Test
    void testCopyModuleFileToFile() throws IOException {
        Path srcFile = tempDir.resolve("src.txt");
        Path destFile = tempDir.resolve("dest.txt");
        String content = "Source file content";
        Files.writeString(srcFile, content);

        String copyModuleMock = 
            "import shutil\n" +
            "import json\n" +
            "args = complex_args\n" +
            "src = args.get('src')\n" +
            "dest = args.get('dest')\n" +
            "changed = False\n" +
            "if src and dest:\n" +
            "    shutil.copy(src, dest)\n" +
            "    changed = True\n" +
            "print(json.dumps({'changed': changed, 'src': src, 'dest': dest}))\n";

        taskExecutor.registerModule("copy", new PythonModule("copy", copyModuleMock));
        
        Map<String, Object> args = Map.of(
            "src", srcFile.toString(),
            "dest", destFile.toString()
        );

        Task task = new Task("test", "copy", args);
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty());

        assertTrue(result.success(), "Module execution should be successful: " + result.message());
        assertTrue(result.changed(), "Module should have changed the state");
        assertTrue(Files.exists(destFile), "Destination file should exist");
        assertEquals(content, Files.readString(destFile), "File content should match");
    }
}
