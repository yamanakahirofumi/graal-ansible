package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToJsonFilterTest {

    @Test
    void testToJsonFilterDirect() {
        ToJsonFilter filter = new ToJsonFilter();
        assertEquals("to_json", filter.getName());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "server1");
        map.put("port", 8080);
        map.put("active", true);

        Object result = filter.filter(map, null);
        assertNotNull(result);
        assertTrue(result instanceof String);
        String json = (String) result;
        assertTrue(json.contains("\"name\":\"server1\""));
        assertTrue(json.contains("\"port\":8080"));
        assertTrue(json.contains("\"active\":true"));
    }

    @Test
    void testToJsonFilterListAndPrimitives() {
        ToJsonFilter filter = new ToJsonFilter();

        // List serialization
        List<Object> list = List.of("alpha", 123, false);
        Object listResult = filter.filter(list, null);
        assertEquals("[\"alpha\",123,false]", listResult);

        // Primitive string
        assertEquals("\"hello world\"", filter.filter("hello world", null));

        // Number
        assertEquals("42", filter.filter(42, null));

        // Boolean
        assertEquals("true", filter.filter(true, null));

        // Null value
        assertEquals("null", filter.filter(null, null));

        // Empty collections
        assertEquals("[]", filter.filter(Collections.emptyList(), null));
        assertEquals("{}", filter.filter(Collections.emptyMap(), null));
    }

    @Test
    void testToJsonViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        Map<String, Object> variables = Map.of(
                "config", Map.of("timeout", 30, "retries", 3)
        );

        String template = "{{ config | to_json }}";
        Object result = resolver.resolveValue(template, variables);

        assertNotNull(result);
        assertTrue(result instanceof String);
        String json = (String) result;
        assertTrue(json.contains("\"timeout\":30"));
        assertTrue(json.contains("\"retries\":3"));
    }

    @Test
    void testToJsonFqcnAliasViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        Map<String, Object> variables = Map.of(
                "fruits", List.of("apple", "banana")
        );

        String template = "{{ fruits | ansible.builtin.to_json }}";
        Object result = resolver.resolveValue(template, variables);

        assertNotNull(result);
        assertEquals("[\"apple\",\"banana\"]", result);
    }
}
