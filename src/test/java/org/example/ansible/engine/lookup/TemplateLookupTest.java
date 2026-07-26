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
}
