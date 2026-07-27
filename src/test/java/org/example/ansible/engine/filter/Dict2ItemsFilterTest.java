package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Dict2ItemsFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void testDict2ItemsSimple() {
        Dict2ItemsFilter filter = new Dict2ItemsFilter();
        Map<String, Object> input = Map.of("a", 1, "b", 2);

        Object result = filter.filter(input, null);

        assertTrue(result instanceof List);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertEquals(2, list.size());

        boolean foundA = false;
        boolean foundB = false;
        for (Map<String, Object> entry : list) {
            if (entry.get("key").equals("a")) {
                assertEquals(1, entry.get("value"));
                foundA = true;
            } else if (entry.get("key").equals("b")) {
                assertEquals(2, entry.get("value"));
                foundB = true;
            }
        }
        assertTrue(foundA);
        assertTrue(foundB);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDict2ItemsCustomNames() {
        Dict2ItemsFilter filter = new Dict2ItemsFilter();
        Map<String, Object> input = Map.of("x", 100);

        // Test passing arguments directly to filter method
        Object result = filter.filter(input, null, "key_name='custom_k'", "value_name='custom_v'");

        assertTrue(result instanceof List);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertEquals(1, list.size());

        Map<String, Object> entry = list.get(0);
        assertEquals("x", entry.get("custom_k"));
        assertEquals(100, entry.get("custom_v"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDict2ItemsIntegrationWithCustomNames() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = Map.of(
            "my_dict", Map.of("k", "v")
        );

        String template = "{{ my_dict | dict2items(key_name='custom_key', value_name='custom_val') }}";
        Object result = resolver.resolveValue(template, variables);

        assertTrue(result instanceof List);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertEquals(1, list.size());

        Map<String, Object> entry = list.get(0);
        assertEquals("k", entry.get("custom_key"));
        assertEquals("v", entry.get("custom_val"));
    }
}
