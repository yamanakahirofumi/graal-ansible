package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Items2DictFilterTest {

    private final Items2DictFilter filter = new Items2DictFilter();
    private final VariableResolver resolver = new VariableResolver();

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictDefaultKeyValues() {
        List<Map<String, Object>> input = List.of(
            Map.of("key", "web", "value", 80),
            Map.of("key", "db", "value", 5432)
        );

        Object result = filter.filter(input, null);
        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;

        assertEquals(2, resultMap.size());
        assertEquals(80, resultMap.get("web"));
        assertEquals(5432, resultMap.get("db"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictCustomKwargs() {
        List<Map<String, Object>> input = List.of(
            Map.of("service_name", "app", "port_num", 8080)
        );

        Map<String, Object> kwargs = Map.of("key_name", "service_name", "value_name", "port_num");
        Object result = filter.filter(input, null, new Object[0], kwargs);

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;

        assertEquals(1, resultMap.size());
        assertEquals(8080, resultMap.get("app"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictPositionalStringArgs() {
        List<Map<String, Object>> input = List.of(
            Map.of("k", "foo", "v", "bar")
        );

        Object result = filter.filter(input, null, new Object[]{"k", "v"}, Map.of());

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;

        assertEquals(1, resultMap.size());
        assertEquals("bar", resultMap.get("foo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictStringKeyValuePairArgs() {
        List<Map<String, Object>> input = List.of(
            Map.of("my_k", "hello", "my_v", "world")
        );

        Object result = filter.filter(input, null, new Object[]{"key_name='my_k'", "value_name='my_v'"}, Map.of());

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;

        assertEquals(1, resultMap.size());
        assertEquals("world", resultMap.get("hello"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictIntegrationWithResolver() {
        List<Map<String, Object>> items = List.of(
            Map.of("key", "host1", "value", "10.0.0.1"),
            Map.of("key", "host2", "value", "10.0.0.2")
        );

        String template = "{{ items | items2dict }}";
        Object result = resolver.resolveValue(template, Map.of("items", items));

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals("10.0.0.1", resultMap.get("host1"));
        assertEquals("10.0.0.2", resultMap.get("host2"));
    }

    @Test
    void testItems2DictNonListAndNullInput() {
        assertEquals("not-a-list", filter.filter("not-a-list", null));
        assertNull(filter.filter(null, null));
    }
}
