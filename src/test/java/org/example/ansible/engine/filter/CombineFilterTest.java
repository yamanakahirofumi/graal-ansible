package org.example.ansible.engine.filter;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombineFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void testCombineMaps() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("a", 1, "b", 2);
        Map<String, Object> map2 = Map.of("b", 3, "c", 4);

        Object result = filter.filter(map1, null, new Object[]{map2}, Map.of());
        assertTrue(result instanceof Map);
        Map<String, Object> resMap = (Map<String, Object>) result;
        assertEquals(3, resMap.size());
        assertEquals(1, resMap.get("a"));
        assertEquals(3, resMap.get("b")); // map2 should override map1
        assertEquals(4, resMap.get("c"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCombineMultipleMaps() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("a", 1, "b", 2);
        Map<String, Object> map2 = Map.of("b", 3, "c", 4);
        Map<String, Object> map3 = Map.of("c", 5, "d", 6);

        Object result = filter.filter(map1, null, new Object[]{map2, map3}, Map.of());
        assertTrue(result instanceof Map);
        Map<String, Object> resMap = (Map<String, Object>) result;
        assertEquals(4, resMap.size());
        assertEquals(1, resMap.get("a"));
        assertEquals(3, resMap.get("b"));
        assertEquals(5, resMap.get("c")); // map3 should override map2
        assertEquals(6, resMap.get("d"));
    }

    @Test
    void testCombineNonMapInput() {
        CombineFilter filter = new CombineFilter();
        Object nonMap = "not-a-map";
        Object result = filter.filter(nonMap, null, new Object[]{Map.of("a", 1)}, Map.of());
        assertEquals("not-a-map", result);
    }

    @Test
    void testCombineNonMapArguments() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("a", 1);
        Object nonMapArg = "not-a-map-arg";

        Object result = filter.filter(map1, null, new Object[]{nonMapArg}, Map.of());
        assertTrue(result instanceof Map);
        assertEquals(map1, result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCombineStringFallback() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("a", 1, "b", 2);

        // Test the older Jinjava call style with String... args fallback
        Object result = filter.filter(map1, null, new String[]{"some_arg"});
        assertTrue(result instanceof Map);
        Map<String, Object> resMap = (Map<String, Object>) result;
        assertEquals(2, resMap.size());
        assertEquals(1, resMap.get("a"));
        assertEquals(2, resMap.get("b"));

        // Non-map input with fallback call style
        assertEquals("string-input", filter.filter("string-input", null, new String[]{"arg"}));
    }
}
