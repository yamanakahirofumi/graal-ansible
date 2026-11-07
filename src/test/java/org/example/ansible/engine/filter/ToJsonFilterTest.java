package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToJsonFilterTest {

    private final ToJsonFilter filter = new ToJsonFilter();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToJsonFilterDirect() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "server1");
        input.put("port", 8080);
        input.put("active", true);

        Object result = filter.filter(input, null);
        assertNotNull(result);
        assertTrue(result instanceof String);
        String jsonStr = (String) result;
        assertTrue(jsonStr.contains("\"name\":\"server1\""));
        assertTrue(jsonStr.contains("\"port\":8080"));
        assertTrue(jsonStr.contains("\"active\":true"));
    }

    @Test
    void testToJsonListAndPrimitives() {
        List<Object> list = List.of("a", 1, false);
        Object listResult = filter.filter(list, null);
        assertEquals("[\"a\",1,false]", listResult);

        assertEquals("\"hello\"", filter.filter("hello", null));
        assertEquals("123", filter.filter(123, null));
        assertEquals("true", filter.filter(true, null));
    }

    @Test
    void testToJsonFilterIntegration() {
        Map<String, Object> data = Map.of("services", List.of("web", "db"));
        String template = "{{ data | to_json }}";

        Object result = resolver.resolveValue(template, Map.of("data", data));
        assertNotNull(result);
        assertTrue(result instanceof String);
        String jsonStr = (String) result;
        assertTrue(jsonStr.contains("\"services\":[\"web\",\"db\"]"));
    }

    @Test
    void testToJsonFqcnAliasIntegration() {
        Map<String, Object> data = Map.of("key", "val");
        String template = "{{ data | ansible.builtin.to_json }}";

        Object result = resolver.resolveValue(template, Map.of("data", data));
        assertNotNull(result);
        assertTrue(result instanceof String);
        String jsonStr = (String) result;
        assertTrue(jsonStr.contains("\"key\":\"val\""));
    }
}
