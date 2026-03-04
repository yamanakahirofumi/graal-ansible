package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FilterIntegrationTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testBoolFilter() {
        Object trueResult = resolver.resolveValue("{{ 'yes' | bool }}", Map.of());
        assertEquals(true, trueResult);

        Object falseResult = resolver.resolveValue("{{ 'off' | bool }}", Map.of());
        assertEquals(false, falseResult);
    }

    @Test
    void testToJsonFilter() {
        Map<String, Object> vars = Map.of("data", Map.of("name", "test", "id", 1));
        String result = (String) resolver.resolveValue("{{ data | to_json }}", vars);
        assertTrue(result.contains("\"name\":\"test\""));
        assertTrue(result.contains("\"id\":1"));
    }

    @Test
    void testToYamlFilter() {
        Map<String, Object> vars = Map.of("data", Map.of("name", "test", "id", 1));
        String result = (String) resolver.resolveValue("{{ data | to_yaml }}", vars);
        assertTrue(result.contains("name: test"));
        assertTrue(result.contains("id: 1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCombineFilter() {
        Map<String, Object> vars = Map.of(
            "dict1", Map.of("a", 1, "b", 2),
            "dict2", Map.of("b", 3, "c", 4)
        );
        // We use a complex expression to test the raw object resolution
        Object resultObj = resolver.resolveValue("{{ dict1 | combine(dict2) }}", vars);
        assertTrue(resultObj instanceof Map, "Expected Map but got " + (resultObj != null ? resultObj.getClass().getName() : "null"));
        Map<String, Object> result = (Map<String, Object>) resultObj;
        assertEquals(1, ((Number)result.get("a")).intValue());
        assertEquals(3, ((Number)result.get("b")).intValue());
        assertEquals(4, ((Number)result.get("c")).intValue());
    }
}
