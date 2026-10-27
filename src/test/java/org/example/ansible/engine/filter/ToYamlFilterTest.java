package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToYamlFilterTest {

    private final ToYamlFilter filter = new ToYamlFilter();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToYamlFilterDirect() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "server1");
        input.put("ports", List.of(80, 443));

        Object result = filter.filter(input, null);
        assertNotNull(result);
        assertTrue(result instanceof String);
        String yamlStr = (String) result;
        assertTrue(yamlStr.contains("name: server1"));
        assertTrue(yamlStr.contains("ports:"));
        assertTrue(yamlStr.contains("- 80"));
        assertTrue(yamlStr.contains("- 443"));
    }

    @Test
    void testToYamlFilterIntegration() {
        Map<String, Object> data = Map.of("env", "production", "debug", false);
        String template = "{{ data | to_yaml }}";

        Object result = resolver.resolveValue(template, Map.of("data", data));
        assertNotNull(result);
        assertTrue(result instanceof String);
        String yamlStr = (String) result;
        assertTrue(yamlStr.contains("env: production"));
        assertTrue(yamlStr.contains("debug: false"));
    }

    @Test
    void testToYamlFqcnAliasIntegration() {
        Map<String, Object> data = Map.of("role", "admin");
        String template = "{{ data | ansible.builtin.to_yaml }}";

        Object result = resolver.resolveValue(template, Map.of("data", data));
        assertNotNull(result);
        assertTrue(result instanceof String);
        String yamlStr = (String) result;
        assertTrue(yamlStr.contains("role: admin"));
    }
}
