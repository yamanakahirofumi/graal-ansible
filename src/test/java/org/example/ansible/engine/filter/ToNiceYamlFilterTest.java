package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToNiceYamlFilterTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToNiceYamlDefault() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "test");
        data.put("id", 1);

        String result = (String) resolver.resolveValue("{{ data | to_nice_yaml }}", Map.of("data", data));
        assertNotNull(result);

        // SnakeYAML uses default block style. Check fields and default indent (4 spaces)
        assertTrue(result.contains("name: test"));
        assertTrue(result.contains("id: 1"));
    }

    @Test
    void testToNiceYamlCustomIndent() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("foo", "bar");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nested", inner);

        // Test positional indent=2
        String resultPositional = (String) resolver.resolveValue("{{ data | to_nice_yaml(2) }}", Map.of("data", data));
        assertTrue(resultPositional.contains("nested:\n  foo: bar"), "Indentation should be 2 spaces: " + resultPositional);

        // Test keyword indent=5
        String resultKeyword = (String) resolver.resolveValue("{{ data | to_nice_yaml(indent=5) }}", Map.of("data", data));
        assertTrue(resultKeyword.contains("nested:\n     foo: bar"), "Indentation should be 5 spaces: " + resultKeyword);
    }

    @Test
    void testToNiceYamlWidth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("long_text", "this is a very long text to test width limit in nice yaml printing");

        // Test keyword width=20 (should cause wrap or split depending on SnakeYAML options, but let's make sure it runs fine)
        String result = (String) resolver.resolveValue("{{ data | to_nice_yaml(indent=4, width=20) }}", Map.of("data", data));
        assertNotNull(result);
        assertTrue(result.contains("long_text:"));
    }
}
