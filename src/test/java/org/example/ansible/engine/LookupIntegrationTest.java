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
        String template = "{{ lookup('env', 'PATH') }}";
        Object result = resolver.resolveValue(template, variables);
        assertNotNull(result);
        assertTrue(result.toString().length() > 0);
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
            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ lookup('file', '" + path + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("Hello World Lookup", result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testFileLookupWithErrorsIgnore() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('file', 'non_existent_file_xyz_999.txt', errors='ignore') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("", result);
    }

    @Test
    void testLookupWithWantlistTrue() throws IOException {
        Path tempFile1 = Files.createTempFile("ansible_wl1", ".txt");
        Path tempFile2 = Files.createTempFile("ansible_wl2", ".txt");
        Files.writeString(tempFile1, "Content A");
        Files.writeString(tempFile2, "Content B");
        try {
            Map<String, Object> variables = new HashMap<>();
            String path1 = tempFile1.toAbsolutePath().toString().replace("\\", "/");
            String path2 = tempFile2.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ lookup('file', '" + path1 + "', '" + path2 + "', wantlist=true) }}";
            Object result = resolver.resolveValue(template, variables);
            assertTrue(result instanceof List);
            List<?> list = (List<?>) result;
            assertEquals(2, list.size());
            assertEquals("Content A", list.get(0));
            assertEquals("Content B", list.get(1));
        } finally {
            Files.deleteIfExists(tempFile1);
            Files.deleteIfExists(tempFile2);
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

        Map<?, ?> item1 = (Map<?, ?>) list.get(0);
        assertTrue(item1.containsKey("key"));
        assertTrue(item1.containsKey("value"));
    }

    @Test
    void testPipeLookup() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('pipe', 'echo HelloPipe') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("HelloPipe", result);
    }

    @Test
    void testPipeLookupWithErrorsIgnore() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('pipe', 'non_existent_cmd_xyz_123', errors='ignore') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("", result);
    }

    @Test
    void testVarsLookupWithDefault() {
        Map<String, Object> variables = Map.of("env_type", "prod", "ansible_prod_host", "10.0.0.1");

        String template1 = "{{ lookup('vars', 'ansible_' + env_type + '_host') }}";
        assertEquals("10.0.0.1", resolver.resolveValue(template1, variables));

        String template2 = "{{ lookup('vars', 'non_existent_var', default='fallback') }}";
        assertEquals("fallback", resolver.resolveValue(template2, variables));
    }

    @Test
    void testFirstFoundLookupWithSkip() {
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('first_found', 'missing1.txt', 'missing2.txt', skip=true) }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("", result);
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

    @Test
    void testLookupErrorsHandling() {
        Map<String, Object> variables = new HashMap<>();

        // Non-existent file with errors='ignore'
        String templateFileIgnore = "{{ query('file', '/non/existent/file.txt', errors='ignore') }}";
        Object resultFileIgnore = resolver.resolveValue(templateFileIgnore, variables);
        assertTrue(resultFileIgnore instanceof List);
        assertTrue(((List<?>) resultFileIgnore).isEmpty());

        // Non-existent vars with errors='warn'
        String templateVarsWarn = "{{ query('vars', 'undefined_variable_xyz', errors='warn') }}";
        Object resultVarsWarn = resolver.resolveValue(templateVarsWarn, variables);
        assertTrue(resultVarsWarn instanceof List);
        assertTrue(((List<?>) resultVarsWarn).isEmpty());
    }
  
    @Test
    void testTemplateLookupConvertData() throws IOException {
        Path tempFile = Files.createTempFile("tpl_data", ".j2");
        Files.writeString(tempFile, "key: {{ app_name }}\nport: {{ app_port }}");

        try {
            Map<String, Object> variables = Map.of("app_name", "webserver", "app_port", 8080);
            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");

            String queryTemplate = "{{ query('template', '" + path + "', convert_data=true) }}";
            Object result = resolver.resolveValue(queryTemplate, variables);
            assertTrue(result instanceof List);
            List<?> list = (List<?>) result;
            assertEquals(1, list.size());
            assertTrue(list.get(0) instanceof Map);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) list.get(0);
            assertEquals("webserver", map.get("key"));
            assertEquals(8080, map.get("port"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
