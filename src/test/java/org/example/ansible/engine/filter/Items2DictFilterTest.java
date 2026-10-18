package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Items2DictFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictDefaultKeys() {
        Items2DictFilter filter = new Items2DictFilter();
        List<Map<String, Object>> input = List.of(
                Map.of("key", "host1", "value", "10.0.0.1"),
                Map.of("key", "host2", "value", "10.0.0.2")
        );

        Object result = filter.filter(input, null);
        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(2, resultMap.size());
        assertEquals("10.0.0.1", resultMap.get("host1"));
        assertEquals("10.0.0.2", resultMap.get("host2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictKwargs() {
        Items2DictFilter filter = new Items2DictFilter();
        List<Map<String, Object>> input = List.of(
                Map.of("name", "db", "port", 5432)
        );

        Map<String, Object> kwargs = Map.of("key_name", "name", "value_name", "port");
        Object result = filter.filter(input, null, new Object[]{}, kwargs);

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals(5432, resultMap.get("db"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictPositionalArgs() {
        Items2DictFilter filter = new Items2DictFilter();
        List<Map<String, Object>> input = List.of(
                Map.of("k_field", "server_a", "v_field", 80)
        );

        Object result = filter.filter(input, null, new Object[]{"k_field", "v_field"}, Map.of());

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals(80, resultMap.get("server_a"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictStringEqualsArgs() {
        Items2DictFilter filter = new Items2DictFilter();
        List<Map<String, Object>> input = List.of(
                Map.of("my_key", "env", "my_val", "prod")
        );

        Object result = filter.filter(input, null, new Object[]{"key_name='my_key'", "value_name='my_val'"}, Map.of());

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals("prod", resultMap.get("env"));
    }

    @Test
    void testItems2DictNonListAndNullInput() {
        Items2DictFilter filter = new Items2DictFilter();

        assertEquals("not-a-list", filter.filter("not-a-list", null));
        assertNull(filter.filter(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictInvalidElements() {
        Items2DictFilter filter = new Items2DictFilter();
        List<Object> input = new ArrayList<>();
        input.add("invalid_string_item");
        input.add(null);
        input.add(Map.of("key", "valid_key", "value", "valid_val"));
        input.add(Map.of("no_key_field", "data"));

        Object result = filter.filter(input, null);
        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals("valid_val", resultMap.get("valid_key"));
    }

    @Test
    void testItems2DictFilterName() {
        Items2DictFilter filter = new Items2DictFilter();
        assertEquals("items2dict", filter.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictIntegrationWithVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        List<Map<String, Object>> items = List.of(
                Map.of("service", "httpd", "status", "active"),
                Map.of("service", "sshd", "status", "running")
        );
        Map<String, Object> vars = Map.of("service_list", items);

        String template = "{{ service_list | items2dict('service', 'status') }}";
        Object result = resolver.resolveValue(template, vars);

        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(2, resultMap.size());
        assertEquals("active", resultMap.get("httpd"));
        assertEquals("running", resultMap.get("sshd"));
    }
}
