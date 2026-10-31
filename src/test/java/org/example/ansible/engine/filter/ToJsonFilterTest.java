package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToJsonFilterTest {

    private final ToJsonFilter filter = new ToJsonFilter();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToJsonSimpleMap() {
        Map<String, Object> map = Map.of("a", 1, "b", "hello");
        Object result = filter.filter(map, null);
        assertTrue(result instanceof String);
        String json = (String) result;
        assertTrue(json.contains("\"a\":1"));
        assertTrue(json.contains("\"b\":\"hello\""));
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }

    @Test
    void testToJsonList() {
        List<Object> list = List.of("first", 42, true);
        Object result = filter.filter(list, null);
        assertTrue(result instanceof String);
        assertEquals("[\"first\",42,true]", result);
    }

    @Test
    void testToJsonPrimitives() {
        assertEquals("123", filter.filter(123, null));
        assertEquals("true", filter.filter(true, null));
        assertEquals("\"text\"", filter.filter("text", null));
    }

    @Test
    void testToJsonNull() {
        assertEquals("null", filter.filter(null, null));
    }

    @Test
    void testToJsonNestedStructures() {
        Map<String, Object> nested = Map.of(
                "server", Map.of("host", "127.0.0.1", "port", 8080),
                "tags", List.of("web", "production")
        );
        Object result = filter.filter(nested, null);
        assertTrue(result instanceof String);
        String json = (String) result;
        assertTrue(json.contains("\"server\":{"));
        assertTrue(json.contains("\"host\":\"127.0.0.1\""));
        assertTrue(json.contains("\"port\":8080"));
        assertTrue(json.contains("\"tags\":[\"web\",\"production\"]"));
    }

    @Test
    void testToJsonTemplateIntegration() {
        Map<String, Object> data = Map.of("name", "ansible", "active", true);
        Map<String, Object> vars = Map.of("data", data);

        // Standard filter
        Object result = resolver.resolveValue("{{ data | to_json }}", vars);
        assertTrue(result instanceof String);
        String json = (String) result;
        assertTrue(json.contains("\"name\":\"ansible\""));
        assertTrue(json.contains("\"active\":true"));

        // FQCN filter alias: ansible.builtin.to_json
        Object fqcnResult = resolver.resolveValue("{{ data | ansible.builtin.to_json }}", vars);
        assertTrue(fqcnResult instanceof String);
        String fqcnJson = (String) fqcnResult;
        assertTrue(fqcnJson.contains("\"name\":\"ansible\""));
        assertTrue(fqcnJson.contains("\"active\":true"));
    }

    @Test
    void testGetName() {
        assertEquals("to_json", filter.getName());
    }
}
