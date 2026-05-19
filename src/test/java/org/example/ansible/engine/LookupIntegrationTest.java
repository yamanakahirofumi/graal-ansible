package org.example.ansible.engine;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LookupIntegrationTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testEnvLookup() {
        Map<String, Object> variables = new HashMap<>();
        // PATH is usually available in most environments
        String template = "{{ lookup('env', 'PATH') }}";
        Object result = resolver.resolveValue(template, variables);
        assertNotNull(result);
        assertTrue(result.toString().length() > 0);
    }

    @Test
    void testEnvLookupMultipleTerms() {
        Map<String, Object> variables = new HashMap<>();
        // PATH exists on both Linux and Windows. USER exists on Linux, USERNAME on Windows.
        String env2 = System.getProperty("os.name").toLowerCase().contains("win") ? "USERNAME" : "USER";
        String template = "{{ query('env', 'PATH', '" + env2 + "') }}";
        Object result = resolver.resolveValue(template, variables);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        // At least PATH should exist. USER/USERNAME might also exist.
        assertTrue(list.size() >= 1);
    }

    @Test
    void testPipeLookupLineSplitting() {
        Map<String, Object> variables = new HashMap<>();
        // Use python to produce predictable multi-line output across platforms
        String template = "{{ query('pipe', 'python3 -c \"print(\\'line1\\'); print(\\'line2\\')\"') }}";
        Object result = resolver.resolveValue(template, variables);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertEquals("line1", list.get(0));
        assertEquals("line2", list.get(1));
    }

    @Test
    void testPipeLookupWantListFalse() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('pipe', 'python3 -c \"print(\\'line1\\'); print(\\'line2\\')\"', wantlist=false) }}";
        Object result = resolver.resolveValue(template, variables);
        // lookup with wantlist=false returns a single string with all output
        String resStr = result.toString().replace("\r\n", "\n");
        assertEquals("line1\nline2", resStr);
    }

    @Test
    void testEnvLookupWithDefault() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('env', 'NON_EXISTENT_VAR_XYZ_123', default='fallback_value') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("fallback_value", result);
    }

    @Test
    void testFileLookup() throws IOException {
        Path tempFile = Files.createTempFile("ansible_test", ".txt");
        Files.writeString(tempFile, "Hello World Lookup");
        try {
            Map<String, Object> variables = new HashMap<>();
            // Use forward slashes for Jinja2 template to avoid lexical errors on Windows
            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ lookup('file', '" + path + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("Hello World Lookup", result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testQuery() throws IOException {
        Path tempFile1 = Files.createTempFile("ansible_test1", ".txt");
        Path tempFile2 = Files.createTempFile("ansible_test2", ".txt");
        Files.writeString(tempFile1, "Content 1");
        Files.writeString(tempFile2, "Content 2");
        try {
            Map<String, Object> variables = new HashMap<>();
            // Use forward slashes for Jinja2 template to avoid lexical errors on Windows
            String path1 = tempFile1.toAbsolutePath().toString().replace("\\", "/");
            String path2 = tempFile2.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ query('file', '" + path1 + "', '" + path2 + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertTrue(result instanceof List);
            List<?> list = (List<?>) result;
            assertEquals(2, list.size());
            assertEquals("Content 1", list.get(0));
            assertEquals("Content 2", list.get(1));
        } finally {
            Files.deleteIfExists(tempFile1);
            Files.deleteIfExists(tempFile2);
        }
    }

    @Test
    void testDictLookup() {
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> myDict = new HashMap<>();
        myDict.put("a", 1);
        myDict.put("b", 2);
        variables.put("my_dict", myDict);

        String template = "{{ query('dict', my_dict) }}";
        Object result = resolver.resolveValue(template, variables);

        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());

        // Items are Map with 'key' and 'value'
        Map<?, ?> item1 = (Map<?, ?>) list.get(0);
        Map<?, ?> item2 = (Map<?, ?>) list.get(1);

        assertTrue(item1.containsKey("key"));
        assertTrue(item1.containsKey("value"));
    }

    @Test
    void testPipeLookup() {
        Map<String, Object> variables = new HashMap<>();
        // Use a simple echo that works across platforms (Windows 'echo' includes arguments as-is)
        String template = "{{ lookup('pipe', 'echo HelloPipe') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("HelloPipe", result);
    }

    @Test
    void testTemplateLookup() throws IOException {
        Path tempFile = Files.createTempFile("ansible_tpl", ".j2");
        Files.writeString(tempFile, "Hello {{ name }}");
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("name", "World");
            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ lookup('template', '" + path + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("Hello World", result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
