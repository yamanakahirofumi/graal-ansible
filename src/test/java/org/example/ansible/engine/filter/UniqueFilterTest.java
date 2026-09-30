package org.example.ansible.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UniqueFilterTest {

    private final UniqueFilter filter = new UniqueFilter();

    @Test
    @SuppressWarnings("unchecked")
    void testUniqueBasicList() {
        List<Object> input = List.of(1, 2, 2, 3, 1, 4);
        Object result = filter.filter(input, null, new Object[]{}, Map.of());
        assertTrue(result instanceof List<?>);
        assertEquals(List.of(1, 2, 3, 4), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUniqueByAttributePositional() {
        List<Map<String, Object>> input = List.of(
                Map.of("id", 1, "name", "a"),
                Map.of("id", 1, "name", "b"),
                Map.of("id", 2, "name", "c")
        );

        Object result = filter.filter(input, null, new Object[]{"id"}, Map.of());
        assertTrue(result instanceof List<?>);
        List<Map<String, Object>> resList = (List<Map<String, Object>>) result;
        assertEquals(2, resList.size());
        assertEquals("a", resList.get(0).get("name"));
        assertEquals("c", resList.get(1).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUniqueByAttributeKwargs() {
        List<Map<String, Object>> input = List.of(
                Map.of("id", 10, "name", "x"),
                Map.of("id", 10, "name", "y"),
                Map.of("id", 20, "name", "z")
        );

        Object result = filter.filter(input, null, new Object[]{}, Map.of("attribute", "id"));
        assertTrue(result instanceof List<?>);
        List<Map<String, Object>> resList = (List<Map<String, Object>>) result;
        assertEquals(2, resList.size());
        assertEquals("x", resList.get(0).get("name"));
        assertEquals("z", resList.get(1).get("name"));
    }

    @Test
    void testUniqueNonCollectionInput() {
        assertEquals("not-a-collection", filter.filter("not-a-collection", null, new Object[]{}, Map.of()));
    }

    @Test
    void testUniqueStringFallback() {
        List<Object> input = List.of("a", "b", "a");
        Object res = filter.filter(input, null, new String[]{});
        assertEquals(List.of("a", "b"), res);
    }
}
