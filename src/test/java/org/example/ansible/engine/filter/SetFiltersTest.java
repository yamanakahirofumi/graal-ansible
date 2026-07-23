package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SetFiltersTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    @SuppressWarnings("unchecked")
    void testDifferenceFilter() {
        // Arrange (準備)
        List<Integer> list1 = List.of(1, 2, 3, 4);
        List<Integer> list2 = List.of(2, 4);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // Act (実行)
        Object resultObj = resolver.resolveValue("{{ list1 | difference(list2) }}", vars);

        // Assert (検証)
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(2, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIntersectFilter() {
        // Arrange (準備)
        List<Integer> list1 = List.of(1, 2, 3, 4);
        List<Integer> list2 = List.of(2, 4, 5);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // Act (実行)
        Object resultObj = resolver.resolveValue("{{ list1 | intersect(list2) }}", vars);

        // Assert (検証)
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(2, result.size());
        assertTrue(result.contains(2));
        assertTrue(result.contains(4));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUnionFilter() {
        // Arrange (準備)
        List<Integer> list1 = List.of(1, 2, 3);
        List<Integer> list2 = List.of(2, 3, 4);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // Act (実行)
        Object resultObj = resolver.resolveValue("{{ list1 | union(list2) }}", vars);

        // Assert (検証)
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(4, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(2));
        assertTrue(result.contains(3));
        assertTrue(result.contains(4));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSymmetricDifferenceFilter() {
        // Arrange (準備)
        List<Integer> list1 = List.of(1, 2, 3);
        List<Integer> list2 = List.of(2, 3, 4);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // Act (実行)
        Object resultObj = resolver.resolveValue("{{ list1 | symmetric_difference(list2) }}", vars);

        // Assert (検証)
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(2, result.size());
        assertTrue(result.contains(1));
        assertTrue(result.contains(4));
    }
}
