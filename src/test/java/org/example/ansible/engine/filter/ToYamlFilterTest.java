package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToYamlFilterTest {

    private final VariableResolver resolver = new VariableResolver();
    private final ToYamlFilter filter = new ToYamlFilter();

    @Test
    void testGetName() {
        assertEquals("to_yaml", filter.getName());
    }

    @Test
    void testToYamlMapAndList() {
        Map<String, Object> mapData = Map.of("name", "ansible", "port", 8080);
        Object mapResult = resolver.resolveValue("{{ data | to_yaml }}", Map.of("data", mapData));
        assertTrue(mapResult instanceof String);
        String mapStr = (String) mapResult;
        assertTrue(mapStr.contains("name: ansible"));
        assertTrue(mapStr.contains("port: 8080"));

        List<Object> listData = List.of("item1", "item2");
        Object listResult = resolver.resolveValue("{{ list_data | to_yaml }}", Map.of("list_data", listData));
        assertTrue(listResult instanceof String);
        String listStr = (String) listResult;
        assertTrue(listStr.contains("- item1"));
        assertTrue(listStr.contains("- item2"));
    }

    @Test
    void testToYamlPrimitives() {
        Object strResult = resolver.resolveValue("{{ 'simple string' | to_yaml }}", Map.of());
        assertTrue(((String) strResult).contains("simple string"));

        Object numResult = resolver.resolveValue("{{ 100 | to_yaml }}", Map.of());
        assertTrue(((String) numResult).contains("100"));
    }

    @Test
    void testToYamlNullAndUndefined() {
        Object nullResult = resolver.resolveValue("{{ null_var | to_yaml }}", Collections.singletonMap("null_var", null));
        assertTrue(((String) nullResult).contains("null"));

        Object undefinedResult = resolver.resolveValue("{{ undefined_var | to_yaml }}", Map.of());
        assertTrue(((String) undefinedResult).contains("null"));
    }
}
