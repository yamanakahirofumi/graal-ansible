package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToJsonFilterTest {

    private final VariableResolver resolver = new VariableResolver();
    private final ToJsonFilter filter = new ToJsonFilter();

    @Test
    void testGetName() {
        assertEquals("to_json", filter.getName());
    }

    @Test
    void testToJsonMapAndList() {
        Map<String, Object> mapData = Map.of("b", 2, "a", 1);
        Object mapResult = resolver.resolveValue("{{ data | to_json }}", Map.of("data", mapData));
        assertTrue(mapResult instanceof String);
        String mapStr = (String) mapResult;
        assertTrue(mapStr.contains("\"a\":1") || mapStr.contains("\"a\": 1"));
        assertTrue(mapStr.contains("\"b\":2") || mapStr.contains("\"b\": 2"));

        List<Object> listData = List.of("apple", "banana", 123);
        Object listResult = resolver.resolveValue("{{ list_data | to_json }}", Map.of("list_data", listData));
        assertTrue(listResult instanceof String);
        String listStr = (String) listResult;
        assertEquals("[\"apple\",\"banana\",123]", listStr);
    }

    @Test
    void testToJsonPrimitives() {
        assertEquals("\"hello\"", resolver.resolveValue("{{ 'hello' | to_json }}", Map.of()));
        assertEquals("42", resolver.resolveValue("{{ 42 | to_json }}", Map.of()));
        assertEquals("true", resolver.resolveValue("{{ true | to_json }}", Map.of()));
    }

    @Test
    void testToJsonNullAndUndefined() {
        assertEquals("null", resolver.resolveValue("{{ null_var | to_json }}", Collections.singletonMap("null_var", null)));
        assertEquals("null", resolver.resolveValue("{{ undefined_var | to_json }}", Map.of()));
    }
}
