package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToYamlFilterTest {

    @Test
    void testToYamlFilterDirect() {
        ToYamlFilter filter = new ToYamlFilter();
        assertEquals("to_yaml", filter.getName());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "nginx");
        map.put("port", 80);
        map.put("ssl", true);

        Object result = filter.filter(map, null);
        assertNotNull(result);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("name: nginx"));
        assertTrue(yaml.contains("port: 80"));
        assertTrue(yaml.contains("ssl: true"));
    }

    @Test
    void testToYamlFilterListAndPrimitives() {
        ToYamlFilter filter = new ToYamlFilter();

        // List serialization
        List<Object> list = List.of("alpha", 100, true);
        Object listResult = filter.filter(list, null);
        assertNotNull(listResult);
        String listYaml = (String) listResult;
        assertTrue(listYaml.contains("- alpha"));
        assertTrue(listYaml.contains("- 100"));
        assertTrue(listYaml.contains("- true"));

        // Primitive string
        String strResult = (String) filter.filter("sample string", null);
        assertTrue(strResult.contains("sample string"));

        // Number
        String numResult = (String) filter.filter(999, null);
        assertTrue(numResult.contains("999"));

        // Boolean
        String boolResult = (String) filter.filter(true, null);
        assertTrue(boolResult.contains("true"));

        // Null value
        String nullResult = (String) filter.filter(null, null);
        assertTrue(nullResult.contains("null"));

        // Empty map
        String emptyMapResult = (String) filter.filter(Collections.emptyMap(), null);
        assertNotNull(emptyMapResult);
    }

    @Test
    void testToYamlViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        Map<String, Object> variables = Map.of(
                "server", Map.of("host", "example.com", "port", 443)
        );

        String template = "{{ server | to_yaml }}";
        Object result = resolver.resolveValue(template, variables);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("host: example.com"));
        assertTrue(yaml.contains("port: 443"));
    }

    @Test
    void testToYamlFqcnAliasViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        Map<String, Object> variables = Map.of(
                "items", List.of("one", "two")
        );

        String template = "{{ items | ansible.builtin.to_yaml }}";
        Object result = resolver.resolveValue(template, variables);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("- one"));
        assertTrue(yaml.contains("- two"));
    }
}
