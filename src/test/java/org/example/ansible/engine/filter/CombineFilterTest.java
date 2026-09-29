package org.example.ansible.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
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
    @SuppressWarnings("unchecked")
    void testCombineRecursive() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("a", Map.of("x", 1, "y", 2), "b", 3);
        Map<String, Object> map2 = Map.of("a", Map.of("y", 20, "z", 30), "c", 4);

        // Shallow merge (recursive=false)
        Object shallowResult = filter.filter(map1, null, new Object[]{map2}, Map.of("recursive", false));
        Map<String, Object> shallowMap = (Map<String, Object>) shallowResult;
        Map<String, Object> shallowSub = (Map<String, Object>) shallowMap.get("a");
        assertEquals(2, shallowSub.size());
        assertEquals(20, shallowSub.get("y"));
        assertNull(shallowSub.get("x"));

        // Recursive merge (recursive=true)
        Object recResult = filter.filter(map1, null, new Object[]{map2}, Map.of("recursive", true));
        Map<String, Object> recMap = (Map<String, Object>) recResult;
        Map<String, Object> recSub = (Map<String, Object>) recMap.get("a");
        assertEquals(3, recSub.size());
        assertEquals(1, recSub.get("x"));
        assertEquals(20, recSub.get("y"));
        assertEquals(30, recSub.get("z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCombineListMergeStrategies() {
        CombineFilter filter = new CombineFilter();
        Map<String, Object> map1 = Map.of("list", List.of(1, 2));
        Map<String, Object> map2 = Map.of("list", List.of(2, 3));

        // Default 'replace'
        Map<String, Object> resReplace = (Map<String, Object>) filter.filter(map1, null, new Object[]{map2}, Map.of());
        assertEquals(List.of(2, 3), resReplace.get("list"));

        // 'append'
        Map<String, Object> resAppend = (Map<String, Object>) filter.filter(map1, null, new Object[]{map2}, Map.of("list_merge", "append"));
        assertEquals(List.of(1, 2, 2, 3), resAppend.get("list"));

        // 'prepend'
        Map<String, Object> resPrepend = (Map<String, Object>) filter.filter(map1, null, new Object[]{map2}, Map.of("list_merge", "prepend"));
        assertEquals(List.of(2, 3, 1, 2), resPrepend.get("list"));

        // 'append_rp'
        Map<String, Object> resAppendRp = (Map<String, Object>) filter.filter(map1, null, new Object[]{map2}, Map.of("list_merge", "append_rp"));
        assertEquals(List.of(1, 2, 3), resAppendRp.get("list"));

        // 'keep'
        Map<String, Object> resKeep = (Map<String, Object>) filter.filter(map1, null, new Object[]{map2}, Map.of("list_merge", "keep"));
        assertEquals(List.of(1, 2), resKeep.get("list"));
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
