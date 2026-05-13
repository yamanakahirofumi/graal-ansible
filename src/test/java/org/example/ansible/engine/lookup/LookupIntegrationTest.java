package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LookupIntegrationTest {

    @TempDir
    Path tempDir;

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testFileLookup() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello lookup");

        Map<String, Object> vars = Map.of("playbook_dir", tempDir.toAbsolutePath().toString());
        Object result = resolver.resolveValue("{{ lookup('file', 'test.txt') }}", vars);

        assertEquals("hello lookup", result);
    }

    @Test
    void testEnvLookup() {
        // Assume PATH exists in the environment
        Object result = resolver.resolveValue("{{ lookup('env', 'PATH') }}", Map.of());
        assertTrue(result.toString().length() > 0);
    }

    @Test
    void testTemplateLookup() throws IOException {
        Path template = tempDir.resolve("test.j2");
        Files.writeString(template, "hello {{ name }}");

        Map<String, Object> vars = Map.of(
                "playbook_dir", tempDir.toAbsolutePath().toString(),
                "name", "world"
        );
        Object result = resolver.resolveValue("{{ lookup('template', 'test.j2') }}", vars);

        assertEquals("hello world", result);
    }

    @Test
    void testPipeLookup() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = isWindows ? "echo hello pipe" : "echo hello pipe";
        Object result = resolver.resolveValue("{{ lookup('pipe', '" + command + "') }}", Map.of());
        // Windows 'echo' might append a newline or space, but we want the core text.
        // Let's use trim() or check contains.
        assertEquals("hello pipe", result.toString().trim());
    }

    @Test
    void testDictLookup() {
        Map<String, Object> myDict = new java.util.TreeMap<>();
        myDict.put("a", 1);
        myDict.put("b", 2);
        Map<String, Object> vars = Map.of("my_dict", myDict);

        // query returns a List of Maps
        Object result = resolver.resolveValue("{{ query('dict', 'my_dict') }}", vars);

        assertTrue(result instanceof List, "Result should be a List");
        List<?> list = (List<?>) result;
        assertEquals(2, list.size(), "List size should be 2");

        // Check contents
        boolean foundA = false;
        boolean foundB = false;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                if ("a".equals(m.get("key")) && Integer.valueOf(1).equals(m.get("value"))) foundA = true;
                if ("b".equals(m.get("key")) && Integer.valueOf(2).equals(m.get("value"))) foundB = true;
            }
        }
        assertTrue(foundA, "Should have found key 'a' with value 1");
        assertTrue(foundB, "Should have found key 'b' with value 2");
    }

    @Test
    void testLookupWithMultipleTerms() throws IOException {
        Files.writeString(tempDir.resolve("f1.txt"), "c1");
        Files.writeString(tempDir.resolve("f2.txt"), "c2");

        Map<String, Object> vars = Map.of("playbook_dir", tempDir.toAbsolutePath().toString());
        Object result = resolver.resolveValue("{{ lookup('file', 'f1.txt', 'f2.txt') }}", vars);

        assertEquals("c1,c2", result);
    }
}
