package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToYamlFilterTest {

    private final ToYamlFilter filter = new ToYamlFilter();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToYamlSimpleMap() {
        Map<String, Object> map = Map.of("foo", "bar", "num", 42);
        Object result = filter.filter(map, null);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("foo: bar"));
        assertTrue(yaml.contains("num: 42"));
    }

    @Test
    void testToYamlList() {
        List<String> list = List.of("alpha", "beta", "gamma");
        Object result = filter.filter(list, null);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("- alpha"));
        assertTrue(yaml.contains("- beta"));
        assertTrue(yaml.contains("- gamma"));
    }

    @Test
    void testToYamlPrimitivesAndNull() {
        assertEquals("123\n", filter.filter(123, null));
        assertEquals("true\n", filter.filter(true, null));
        assertEquals("text\n", filter.filter("text", null));
        assertEquals("null\n", filter.filter(null, null));
    }

    @Test
    void testToYamlNestedStructures() {
        Map<String, Object> nested = Map.of(
                "services", List.of("nginx", "postgres"),
                "config", Map.of("port", 80, "ssl", true)
        );
        Object result = filter.filter(nested, null);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("services:"));
        assertTrue(yaml.contains("- nginx"));
        assertTrue(yaml.contains("- postgres"));
        assertTrue(yaml.contains("config:"));
        assertTrue(yaml.contains("port: 80"));
        assertTrue(yaml.contains("ssl: true"));
    }

    @Test
    void testToYamlTemplateIntegration() {
        Map<String, Object> data = Map.of("service", "web", "port", 8080);
        Map<String, Object> vars = Map.of("data", data);

        // Standard filter
        Object result = resolver.resolveValue("{{ data | to_yaml }}", vars);
        assertTrue(result instanceof String);
        String yaml = (String) result;
        assertTrue(yaml.contains("service: web"));
        assertTrue(yaml.contains("port: 8080"));

        // FQCN filter alias: ansible.builtin.to_yaml
        Object fqcnResult = resolver.resolveValue("{{ data | ansible.builtin.to_yaml }}", vars);
        assertTrue(fqcnResult instanceof String);
        String fqcnYaml = (String) fqcnResult;
        assertTrue(fqcnYaml.contains("service: web"));
        assertTrue(fqcnYaml.contains("port: 8080"));
    }

    @Test
    void testGetName() {
        assertEquals("to_yaml", filter.getName());
    }
}
