package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Items2DictFilterTest {

    @Test
    void testItems2DictDefaultKeyAndValue() {
        Items2DictFilter filter = new Items2DictFilter();
        assertEquals("items2dict", filter.getName());

        List<Map<String, Object>> input = List.of(
                Map.of("key", "host1", "value", "192.168.1.10"),
                Map.of("key", "host2", "value", "192.168.1.20")
        );

        Object result = filter.filter(input, null);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(2, resultMap.size());
        assertEquals("192.168.1.10", resultMap.get("host1"));
        assertEquals("192.168.1.20", resultMap.get("host2"));
    }

    @Test
    void testItems2DictCustomKeyAndValueNamesViaKwargs() {
        Items2DictFilter filter = new Items2DictFilter();

        List<Map<String, Object>> input = List.of(
                Map.of("name", "web", "port", 80),
                Map.of("name", "db", "port", 5432)
        );

        Map<String, Object> kwargs = Map.of(
                "key_name", "name",
                "value_name", "port"
        );

        Object result = filter.filter(input, null, new Object[0], kwargs);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(2, resultMap.size());
        assertEquals(80, resultMap.get("web"));
        assertEquals(5432, resultMap.get("db"));
    }

    @Test
    void testItems2DictCustomKeyAndValueNamesViaPositionalArgs() {
        Items2DictFilter filter = new Items2DictFilter();

        List<Map<String, Object>> input = List.of(
                Map.of("service", "httpd", "status", "running")
        );

        Object result = filter.filter(input, null, "service", "status");
        assertNotNull(result);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals("running", resultMap.get("httpd"));
    }

    @Test
    void testItems2DictCustomKeyAndValueNamesViaStringEqualityArgs() {
        Items2DictFilter filter = new Items2DictFilter();

        List<Map<String, Object>> input = List.of(
                Map.of("my_key", "itemA", "my_val", 100)
        );

        Object result = filter.filter(input, null, "key_name='my_key'", "value_name='my_val'");
        assertNotNull(result);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(1, resultMap.size());
        assertEquals(100, resultMap.get("itemA"));
    }

    @Test
    void testItems2DictNonListAndNullInput() {
        Items2DictFilter filter = new Items2DictFilter();

        // Non-list returns input unchanged
        assertEquals("not-a-list", filter.filter("not-a-list", null));
        assertEquals(12345, filter.filter(12345, null));
        assertNull(filter.filter(null, null));

        // Empty list returns empty map
        Object emptyListResult = filter.filter(Collections.emptyList(), null);
        assertNotNull(emptyListResult);
        assertTrue(emptyListResult instanceof Map);
        assertTrue(((Map<?, ?>) emptyListResult).isEmpty());
    }

    @Test
    void testItems2DictViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        List<Map<String, Object>> items = List.of(
                Map.of("service", "nginx", "port", 80),
                Map.of("service", "ssh", "port", 22)
        );

        Map<String, Object> variables = Map.of("service_list", items);

        // Positional args in Jinja2 template
        String templatePos = "{{ service_list | items2dict('service', 'port') }}";
        Object resultPos = resolver.resolveValue(templatePos, variables);
        assertNotNull(resultPos);
        assertTrue(resultPos instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMapPos = (Map<Object, Object>) resultPos;
        assertEquals(80, resultMapPos.get("nginx"));
        assertEquals(22, resultMapPos.get("ssh"));

        // Keyword args in Jinja2 template
        String templateKw = "{{ service_list | items2dict(key_name='service', value_name='port') }}";
        Object resultKw = resolver.resolveValue(templateKw, variables);
        assertNotNull(resultKw);
        assertTrue(resultKw instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMapKw = (Map<Object, Object>) resultKw;
        assertEquals(80, resultMapKw.get("nginx"));
        assertEquals(22, resultMapKw.get("ssh"));
    }

    @Test
    void testItems2DictFqcnAliasViaVariableResolver() {
        VariableResolver resolver = new VariableResolver();

        List<Map<String, Object>> items = List.of(
                Map.of("key", "k1", "value", "v1")
        );

        Map<String, Object> variables = Map.of("items", items);

        String template = "{{ items | ansible.builtin.items2dict }}";
        Object result = resolver.resolveValue(template, variables);
        assertNotNull(result);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals("v1", resultMap.get("k1"));
    }
}
