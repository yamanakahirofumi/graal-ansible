package org.example.ansible.module.python;

import org.example.ansible.engine.TaskResult;
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

    @Test
    void testCopyModuleWithContent() throws IOException {
        Path destFile = tempDir.resolve("hello.txt");
        String content = "Hello from GraalPy Ansible!";

        // 本来の ansible.builtin.copy モジュールの動作を模倣する Python スクリプト
        // 実際には本家の copy.py をロードするが、ここでは簡易版で動作確認
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

        PythonModule module = new PythonModule("copy", copyModuleMock);
        
        Map<String, Object> args = Map.of(
            "content", content,
            "dest", destFile.toString()
        );

        TaskResult result = module.execute(args);

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

        PythonModule module = new PythonModule("copy", copyModuleMock);
        
        Map<String, Object> args = Map.of(
            "src", srcFile.toString(),
            "dest", destFile.toString()
        );

        TaskResult result = module.execute(args);

        assertTrue(result.success(), "Module execution should be successful: " + result.message());
        assertTrue(result.changed(), "Module should have changed the state");
        assertTrue(Files.exists(destFile), "Destination file should exist");
        assertEquals(content, Files.readString(destFile), "File content should match");
    }
}
