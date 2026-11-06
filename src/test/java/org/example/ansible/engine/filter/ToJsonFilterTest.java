package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToJsonFilterTest {

    @Test
    void testToJsonMapAndList() {
        ToJsonFilter filter = new ToJsonFilter();

        Map<String, Object> mapInput = Map.of("name", "ansible", "port", 8080);
        Object jsonMap = filter.filter(mapInput, null);
        assertTrue(jsonMap instanceof String);
        String jsonMapStr = (String) jsonMap;
        assertTrue(jsonMapStr.contains("\"name\":\"ansible\""));
        assertTrue(jsonMapStr.contains("\"port\":8080"));

        List<Object> listInput = List.of("item1", 123, true);
        Object jsonList = filter.filter(listInput, null);
        assertTrue(jsonList instanceof String);
        String jsonListStr = (String) jsonList;
        assertEquals("[\"item1\",123,true]", jsonListStr);
    }

    @Test
    void testToJsonPrimitivesAndNull() {
        ToJsonFilter filter = new ToJsonFilter();

        assertEquals("\"hello\"", filter.filter("hello", null));
        assertEquals("42", filter.filter(42, null));
        assertEquals("true", filter.filter(true, null));
        assertEquals("null", filter.filter(null, null));
    }

    @Test
    void testToJsonFilterName() {
        ToJsonFilter filter = new ToJsonFilter();
        assertEquals("to_json", filter.getName());
    }

    @Test
    void testToJsonIntegrationWithVariableResolver() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> vars = Map.of("data", Map.of("k", "v"));

        Object result = resolver.resolveValue("{{ data | to_json }}", vars);
        assertTrue(result instanceof String);
        assertEquals("{\"k\":\"v\"}", result);
    }
}
