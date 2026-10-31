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
    void testItems2DictDefault() {
        List<Map<String, Object>> input = List.of(
                Map.of("key", "a", "value", 1),
                Map.of("key", "b", "value", 2)
        );

        Object result = filter.filter(input, null);
        assertTrue(result instanceof Map);
        Map<Object, Object> resultMap = (Map<Object, Object>) result;
        assertEquals(2, resultMap.size());
        assertEquals(1, resultMap.get("a"));
        assertEquals(2, resultMap.get("b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictWithCustomKeyAndValueNames() {
        List<Map<String, Object>> input = List.of(
                Map.of("service", "web", "port", 80),
                Map.of("service", "db", "port", 5432)
        );

        // Positional string args
        Object resultPos = filter.filter(input, null, "service", "port");
        assertTrue(resultPos instanceof Map);
        Map<Object, Object> posMap = (Map<Object, Object>) resultPos;
        assertEquals(80, posMap.get("web"));
        assertEquals(5432, posMap.get("db"));

        // Key=Value string args
        Object resultKeyValue = filter.filter(input, null, "key_name=service", "value_name=port");
        assertTrue(resultKeyValue instanceof Map);
        Map<Object, Object> kvMap = (Map<Object, Object>) resultKeyValue;
        assertEquals(80, kvMap.get("web"));
        assertEquals(5432, kvMap.get("db"));

        // Kwargs
        Object resultKwargs = filter.filter(input, null, new Object[0], Map.of("key_name", "service", "value_name", "port"));
        assertTrue(resultKwargs instanceof Map);
        Map<Object, Object> kwargsMap = (Map<Object, Object>) resultKwargs;
        assertEquals(80, kwargsMap.get("web"));
        assertEquals(5432, kwargsMap.get("db"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testItems2DictTemplateIntegration() {
        List<Map<String, Object>> input = List.of(
                Map.of("key", "k1", "value", "v1"),
                Map.of("key", "k2", "value", "v2")
        );
        Map<String, Object> vars = Map.of("items_list", input);

        Object result = resolver.resolveValue("{{ items_list | items2dict }}", vars);
        assertTrue(result instanceof Map);
        Map<Object, Object> map = (Map<Object, Object>) result;
        assertEquals("v1", map.get("k1"));
        assertEquals("v2", map.get("k2"));

        // Template with positional custom key and value names
        List<Map<String, Object>> customInput = List.of(
                Map.of("name", "http", "port", 8080)
        );
        Object customResult = resolver.resolveValue("{{ custom_list | items2dict('name', 'port') }}", Map.of("custom_list", customInput));
        assertTrue(customResult instanceof Map);
        Map<Object, Object> customMap = (Map<Object, Object>) customResult;
        assertEquals(8080, customMap.get("http"));

        // Template with FQCN filter alias
        Object fqcnResult = resolver.resolveValue("{{ items_list | ansible.builtin.items2dict }}", vars);
        assertTrue(fqcnResult instanceof Map);
        Map<Object, Object> fqcnMap = (Map<Object, Object>) fqcnResult;
        assertEquals("v1", fqcnMap.get("k1"));
    }

    @Test
    void testItems2DictEdgeCases() {
        // Non-list input is returned as-is
        assertEquals("not_a_list", filter.filter("not_a_list", null));
        assertNull(filter.filter(null, null));

        // Empty list returns empty map
        Object emptyResult = filter.filter(List.of(), null);
        assertTrue(emptyResult instanceof Map);
        assertTrue(((Map<?, ?>) emptyResult).isEmpty());

        // Elements that are not Maps are skipped
        List<Object> mixedList = List.of(
                "just_a_string",
                123,
                Map.of("key", "valid_key", "value", "valid_value")
        );
        Object mixedResult = filter.filter(mixedList, null);
        assertTrue(mixedResult instanceof Map);
        Map<?, ?> mixedMap = (Map<?, ?>) mixedResult;
        assertEquals(1, mixedMap.size());
        assertEquals("valid_value", mixedMap.get("valid_key"));

        // Elements where the key is null or missing are skipped
        List<Map<String, Object>> missingKeyList = List.of(
                Map.of("value", "orphan_value"),
                Map.of("key", "good_key", "value", "good_value")
        );
        Object missingKeyResult = filter.filter(missingKeyList, null);
        assertTrue(missingKeyResult instanceof Map);
        Map<?, ?> missingKeyMap = (Map<?, ?>) missingKeyResult;
        assertEquals(1, missingKeyMap.size());
        assertEquals("good_value", missingKeyMap.get("good_key"));
    }

    @Test
    void testGetName() {
        assertEquals("items2dict", filter.getName());
    }
}
