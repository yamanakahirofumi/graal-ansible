package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileLookupTest {

    @Test
    void testFileLookupAbsolute() throws IOException {
        Path tempFile = Files.createTempFile("file_lookup_test", ".txt");
        Files.writeString(tempFile, "File Content Absolute");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");

            String template = "{{ lookup('file', '" + path + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("File Content Absolute", result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testFileLookupRelativeWithPlaybookDir() throws IOException {
        Path tempDir = Files.createTempDirectory("playbook_dir_test");
        Path tempFile = Files.createFile(tempDir.resolve("relative_file.txt"));
        Files.writeString(tempFile, "File Content Relative");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            variables.put("playbook_dir", tempDir.toAbsolutePath().toString().replace("\\", "/"));

            String template = "{{ lookup('file', 'relative_file.txt') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("File Content Relative", result);
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void testFileLookupNonExistent() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('file', 'non_existent_file_xyz.txt') }}";
        assertThrows(RuntimeException.class, () -> resolver.resolveValue(template, variables));
    }
}
