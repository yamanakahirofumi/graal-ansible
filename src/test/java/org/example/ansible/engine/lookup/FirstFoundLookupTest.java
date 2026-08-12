package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FirstFoundLookupTest {

    @Test
    void testFirstFoundSimpleFiles(@TempDir Path tempDir) throws IOException {
        final Path file1 = tempDir.resolve("file1.txt");
        final Path file2 = tempDir.resolve("file2.txt");

        Files.writeString(file2, "content2");

        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        // Since file1 does not exist but file2 does, it should find file2.
        final String template = "{{ lookup('first_found', 'file1.txt', 'file2.txt') }}";
        final Object result = resolver.resolveValue(template, variables);

        assertEquals(file2.toAbsolutePath().toString(), result);
    }

    @Test
    void testFirstFoundWithPaths(@TempDir Path tempDir) throws IOException {
        final Path dirA = tempDir.resolve("dirA");
        final Path dirB = tempDir.resolve("dirB");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        final Path fileInB = dirB.resolve("test.txt");
        Files.writeString(fileInB, "hello from B");

        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        // Look for test.txt in paths dirA, dirB
        final String template = "{{ lookup('first_found', files=['test.txt'], paths=['dirA', 'dirB']) }}";
        final Object result = resolver.resolveValue(template, variables);

        assertEquals(fileInB.toAbsolutePath().toString(), result);
    }

    @Test
    void testFirstFoundWithCommaSeparated(@TempDir Path tempDir) throws IOException {
        final Path fileTarget = tempDir.resolve("target.txt");
        Files.writeString(fileTarget, "target content");

        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        final String template = "{{ lookup('first_found', 'missing1.txt,missing2.txt,target.txt') }}";
        final Object result = resolver.resolveValue(template, variables);

        assertEquals(fileTarget.toAbsolutePath().toString(), result);
    }

    @Test
    void testFirstFoundAbsolutePaths(@TempDir Path tempDir) throws IOException {
        final Path file1 = tempDir.resolve("abs1.txt");
        Files.writeString(file1, "abs content");

        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();

        final String template = "{{ lookup('first_found', '" + file1.toAbsolutePath().toString() + "') }}";
        final Object result = resolver.resolveValue(template, variables);

        assertEquals(file1.toAbsolutePath().toString(), result);
    }

    @Test
    void testFirstFoundNotFoundThrowsException(@TempDir Path tempDir) {
        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        final String template = "{{ lookup('first_found', 'nonexistent.txt') }}";

        final RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            resolver.resolveValue(template, variables);
        });
        System.out.println("EXCEPTION_MSG: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("Error invoking function 'lookup'"));
    }

    @Test
    void testFirstFoundWithSkip(@TempDir Path tempDir) {
        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        final String template = "{{ lookup('first_found', 'nonexistent.txt', skip=true) }}";
        final Object result = resolver.resolveValue(template, variables);

        // Standard Ansible query with skip returns an empty list or empty representation,
        // in lookupFunc it is joined by commas, so it returns empty string "".
        assertEquals("", result);
    }

    @Test
    void testFirstFoundWithDictParameter(@TempDir Path tempDir) throws IOException {
        final Path dirX = tempDir.resolve("dirX");
        Files.createDirectories(dirX);
        final Path fileY = dirX.resolve("fileY.txt");
        Files.writeString(fileY, "contentY");

        final VariableResolver resolver = new VariableResolver();
        final Map<String, Object> variables = new HashMap<>();
        variables.put("playbook_dir", tempDir.toAbsolutePath().toString());

        // Use inline map syntax instead of dict function
        final String template = "{{ lookup('first_found', {'files': ['fileY.txt'], 'paths': ['dirX']}) }}";
        final Object result = resolver.resolveValue(template, variables);

        assertEquals(fileY.toAbsolutePath().toString(), result);
    }
}
