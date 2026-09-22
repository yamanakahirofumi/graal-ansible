package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        Object resultObj = resolver.resolveValue("{{ dict1 | combine(dict2) }}", vars);
        assertTrue(resultObj instanceof Map, "Expected Map but got " + (resultObj != null ? resultObj.getClass().getName() : "null"));
        Map<String, Object> result = (Map<String, Object>) resultObj;
        assertEquals(1, ((Number)result.get("a")).intValue());
        assertEquals(3, ((Number)result.get("b")).intValue());
        assertEquals(4, ((Number)result.get("c")).intValue());
    }

    @Test
    void testRegexReplaceFilter() {
        assertEquals("graal-2.17", resolver.resolveValue("{{ 'ansible-2.17' | regex_replace('^ansible-', 'graal-') }}", Map.of()));
        assertEquals("foobar", resolver.resolveValue("{{ 'foo123bar' | regex_replace('[0-9]+') }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | regex_replace('[0-9]+') }}", Map.of()));
    }

    @Test
    void testTernaryFilter() {
        assertEquals("enabled", resolver.resolveValue("{{ true | ternary('enabled', 'disabled') }}", Map.of()));
        assertEquals("disabled", resolver.resolveValue("{{ false | ternary('enabled', 'disabled') }}", Map.of()));
        assertEquals("unknown", resolver.resolveValue("{{ undefined_var | ternary('yes', 'no', 'unknown') }}", Map.of()));
    }

    @Test
    void testIpAddrFilter() {
        assertEquals("192.168.1.1", resolver.resolveValue("{{ '192.168.1.1' | ipaddr }}", Map.of()));
        assertEquals("2001:db8::1", resolver.resolveValue("{{ '2001:db8::1' | ipaddr }}", Map.of()));
        assertEquals(false, resolver.resolveValue("{{ 'invalid-ip' | ipaddr }}", Map.of()));
    }

    @Test
    void testDefaultFilter() {
        assertEquals("8080", resolver.resolveValue("{{ custom_port | default(8080) }}", Map.of()).toString());
        assertEquals(9000, resolver.resolveValue("{{ custom_port | default(8080) }}", Map.of("custom_port", 9000)));
    }

    @Test
    void testFlattenFilter() {
        List<Object> nested = List.of(1, List.of(2, List.of(3, 4)), 5);
        Object resultAll = resolver.resolveValue("{{ nested | flatten }}", Map.of("nested", nested));
        assertEquals(List.of(1, 2, 3, 4, 5), resultAll);

        Object resultLevel1 = resolver.resolveValue("{{ nested | flatten(1) }}", Map.of("nested", nested));
        assertEquals(List.of(1, 2, List.of(3, 4), 5), resultLevel1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testDict2ItemsAndItems2DictFilters() {
        Map<String, Object> inputDict = Map.of("web", 80, "db", 5432);
        Object items = resolver.resolveValue("{{ inputDict | dict2items }}", Map.of("inputDict", inputDict));
        assertTrue(items instanceof List);
        List<Map<String, Object>> itemsList = (List<Map<String, Object>>) items;
        assertEquals(2, itemsList.size());

        Object backToDict = resolver.resolveValue("{{ itemsList | items2dict }}", Map.of("itemsList", itemsList));
        assertTrue(backToDict instanceof Map);
        Map<String, Object> restoredDict = (Map<String, Object>) backToDict;
        assertEquals(80, restoredDict.get("web"));
        assertEquals(5432, restoredDict.get("db"));

        // Custom key_name and value_name
        Object customItems = resolver.resolveValue("{{ inputDict | dict2items(key_name='service', value_name='port') }}", Map.of("inputDict", inputDict));
        assertTrue(customItems instanceof List);
        List<Map<String, Object>> customList = (List<Map<String, Object>>) customItems;
        Map<String, Object> firstEntry = customList.get(0);
        assertTrue(firstEntry.containsKey("service") && firstEntry.containsKey("port"));

        Object customRestored = resolver.resolveValue("{{ customList | items2dict('service', 'port') }}", Map.of("customList", customList));
        assertTrue(customRestored instanceof Map);
        assertEquals(80, ((Map<String, Object>) customRestored).get("web"));
    }

    @Test
    void testSetOperationFilters() {
        List<Integer> list1 = List.of(1, 2, 3, 4);
        List<Integer> list2 = List.of(2, 4, 5);
        Map<String, Object> vars = Map.of("l1", list1, "l2", list2);

        assertEquals(List.of(1, 3), resolver.resolveValue("{{ l1 | difference(l2) }}", vars));
        assertEquals(List.of(2, 4), resolver.resolveValue("{{ l1 | intersect(l2) }}", vars));
        assertEquals(List.of(1, 2, 3, 4, 5), resolver.resolveValue("{{ l1 | union(l2) }}", vars));
        assertEquals(List.of(1, 3, 5), resolver.resolveValue("{{ l1 | symmetric_difference(l2) }}", vars));
    }

    @Test
    void testPathAndQuoteFilters() {
        assertEquals("nginx.conf", resolver.resolveValue("{{ '/etc/nginx/nginx.conf' | basename }}", Map.of()));
        assertEquals("/etc/nginx", resolver.resolveValue("{{ '/etc/nginx/nginx.conf' | dirname }}", Map.of()));
        assertEquals("'hello '\\''world'\\'''", resolver.resolveValue("{{ \"hello 'world'\" | quote }}", Map.of()));
    }

    @Test
    void testB64EncodeDecodeAndUrlencodeFilters() {
        assertEquals("aGVsbG8=", resolver.resolveValue("{{ 'hello' | b64encode }}", Map.of()));
        assertEquals("hello", resolver.resolveValue("{{ 'aGVsbG8=' | b64decode }}", Map.of()));

        assertEquals("hello%20world", resolver.resolveValue("{{ 'hello world' | urlencode }}", Map.of()));
    }

    @Test
    void testUniqueAndMandatoryFilters() {
        List<Integer> duplicates = List.of(1, 2, 2, 3, 1);
        assertEquals(List.of(1, 2, 3), resolver.resolveValue("{{ duplicates | unique }}", Map.of("duplicates", duplicates)));

        assertEquals("valid", resolver.resolveValue("{{ 'valid' | mandatory }}", Map.of()));
        assertThrows(RuntimeException.class, () -> resolver.resolveValue("{{ missing_var | mandatory('Var is required') }}", Map.of()));
    }

    @Test
    void testToNiceJsonAndToNiceYamlFilters() {
        Map<String, Object> data = Map.of("b", 2, "a", 1);
        Object jsonResult = resolver.resolveValue("{{ data | to_nice_json(2) }}", Map.of("data", data));
        assertTrue(jsonResult instanceof String);
        String jsonStr = (String) jsonResult;
        assertTrue(jsonStr.contains("\"a\""));

        Object yamlResult = resolver.resolveValue("{{ data | to_nice_yaml(2, 100) }}", Map.of("data", data));
        assertTrue(yamlResult instanceof String);
        String yamlStr = (String) yamlResult;
        assertTrue(yamlStr.contains("a:") || yamlStr.contains("a :"));
    }
}
