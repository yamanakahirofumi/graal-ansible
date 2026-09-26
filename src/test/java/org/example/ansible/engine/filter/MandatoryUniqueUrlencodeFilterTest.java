package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MandatoryUniqueUrlencodeFilterTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testMandatoryNormal() {
        assertEquals("hello", resolver.resolveValue("{{ 'hello' | mandatory }}", Map.of()));
        assertEquals(123, resolver.resolveValue("{{ val | mandatory }}", Map.of("val", 123)));
    }

    @Test
    void testMandatoryExceptionDefaultMessage() {
        assertThrows(RuntimeException.class, () -> resolver.resolveValue("{{ undefined_var | mandatory }}", Map.of()));
        assertThrows(RuntimeException.class, () -> resolver.resolveValue("{{ '' | mandatory }}", Map.of()));
    }

    @Test
    void testMandatoryExceptionCustomMessage() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            resolver.resolveValue("{{ undefined_var | mandatory('Custom error msg') }}", Map.of())
        );
        assertTrue(ex.getMessage().contains("Custom error msg"));
    }

    @Test
    void testUniqueNormalList() {
        Object result = resolver.resolveValue("{{ [1, 2, 2, 3, 1] | unique }}", Map.of());
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(3, list.size());
        assertEquals(1, ((Number) list.get(0)).intValue());
        assertEquals(2, ((Number) list.get(1)).intValue());
        assertEquals(3, ((Number) list.get(2)).intValue());
    }

    @Test
    void testUniqueAttributeList() {
        List<Map<String, Object>> items = List.of(
            Map.of("id", 1, "name", "a"),
            Map.of("id", 1, "name", "b"),
            Map.of("id", 2, "name", "c")
        );
        Object result = resolver.resolveValue("{{ items | unique('id') }}", Map.of("items", items));
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertEquals(Map.of("id", 1, "name", "a"), list.get(0));
        assertEquals(Map.of("id", 2, "name", "c"), list.get(1));
    }

    @Test
    void testUniqueNonCollectionAndNull() {
        assertEquals("string_val", resolver.resolveValue("{{ 'string_val' | unique }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | unique }}", Map.of()));
    }

    @Test
    void testUrlencodeString() {
        assertEquals("hello%20world%26foo%3Dbar", resolver.resolveValue("{{ 'hello world&foo=bar' | urlencode }}", Map.of()));
    }

    @Test
    void testUrlencodeMap() {
        Map<String, Object> map = Map.of("name", "john doe", "city", "new york");
        Object result = resolver.resolveValue("{{ map | urlencode }}", Map.of("map", map));
        assertTrue(result instanceof String);
        String urlStr = (String) result;
        assertTrue(urlStr.contains("name=john%20doe"));
        assertTrue(urlStr.contains("city=new%20york"));
        assertTrue(urlStr.contains("&"));
    }

    @Test
    void testUrlencodeNullAndUndefined() {
        assertEquals("", resolver.resolveValue("{{ undefined_var | urlencode }}", Map.of()));
    }
}
