package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TemplateLookupTest {

    @Test
    void testTemplateLookupSuccess() throws IOException {
        Path tempFile = Files.createTempFile("tpl_lookup", ".j2");
        Files.writeString(tempFile, "Hello {{ target_name }}! From {{ sender }}");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            variables.put("target_name", "Ansible");
            variables.put("sender", "Jules");

            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ lookup('template', '" + path + "') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("Hello Ansible! From Jules", result);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testTemplateLookupRelative() throws IOException {
        Path tempDir = Files.createTempDirectory("tpl_lookup_dir");
        Path tempFile = Files.createFile(tempDir.resolve("my_template.j2"));
        Files.writeString(tempFile, "Value is {{ value_to_render }}");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            variables.put("playbook_dir", tempDir.toAbsolutePath().toString().replace("\\", "/"));
            variables.put("value_to_render", "42");

            String template = "{{ lookup('template', 'my_template.j2') }}";
            Object result = resolver.resolveValue(template, variables);
            assertEquals("Value is 42", result);
        } finally {
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void testTemplateLookupNonExistent() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        String template = "{{ lookup('template', 'missing_template_file_abc.j2') }}";
        assertThrows(RuntimeException.class, () -> resolver.resolveValue(template, variables));
    }

    @Test
    void testTemplateLookupConvertDataTrue() throws IOException {
        Path tempFile = Files.createTempFile("tpl_data", ".j2");
        Files.writeString(tempFile, "key: {{ app_name }}\nport: {{ app_port }}");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            variables.put("app_name", "webserver");
            variables.put("app_port", 8080);

            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ query('template', '" + path + "', convert_data=true) }}";
            Object result = resolver.resolveValue(template, variables);

            assertTrue(result instanceof java.util.List<?>, "query result should be a list");
            java.util.List<?> list = (java.util.List<?>) result;
            assertEquals(1, list.size());
            assertTrue(list.get(0) instanceof Map<?, ?>, "element should be converted to a Map");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) list.get(0);
            assertEquals("webserver", map.get("key"));
            assertEquals(8080, map.get("port"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testTemplateLookupConvertDataFalse() throws IOException {
        Path tempFile = Files.createTempFile("tpl_data_raw", ".j2");
        Files.writeString(tempFile, "key: {{ app_name }}\nport: {{ app_port }}");

        try {
            VariableResolver resolver = new VariableResolver();
            Map<String, Object> variables = new HashMap<>();
            variables.put("app_name", "webserver");
            variables.put("app_port", 8080);

            String path = tempFile.toAbsolutePath().toString().replace("\\", "/");
            String template = "{{ query('template', '" + path + "', convert_data=false) }}";
            Object result = resolver.resolveValue(template, variables);

            assertTrue(result instanceof java.util.List<?>, "query result should be a list");
            java.util.List<?> list = (java.util.List<?>) result;
            assertEquals(1, list.size());
            assertTrue(list.get(0) instanceof String, "element should remain raw String when convert_data=false");
            assertEquals("key: webserver\nport: 8080", list.get(0));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
