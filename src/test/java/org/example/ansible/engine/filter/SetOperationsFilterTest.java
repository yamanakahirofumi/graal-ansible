package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jinja2 集合演算フィルター (difference, intersect, union, symmetric_difference) の単体テスト。
 * docs/implementation/Variables-Templating.md Section 4.1 & 4.4 の仕様に基づく検証を行います。
 */
class SetOperationsFilterTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    @SuppressWarnings("unchecked")
    void testDifferenceWithDuplicatesAndOrder() {
        // 準備: 重複を含むリスト
        List<String> list1 = List.of("a", "b", "c", "b", "a");
        List<String> list2 = List.of("b");
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // 実行
        Object resultObj = resolver.resolveValue("{{ list1 | difference(list2) }}", vars);

        // 検証: 重複排除かつ "a", "c" の順序維持
        assertTrue(resultObj instanceof List);
        List<String> result = (List<String>) resultObj;
        assertEquals(List.of("a", "c"), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIntersectWithOverlap() {
        // 準備
        List<Integer> list1 = List.of(1, 2, 2, 3, 4);
        List<Integer> list2 = List.of(2, 3, 3, 5);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // 実行
        Object resultObj = resolver.resolveValue("{{ list1 | intersect(list2) }}", vars);

        // 検証: 重複排除された共通要素 [2, 3]
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(List.of(2, 3), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUnionPreservesOrderAndUniqueness() {
        // 準備
        List<String> list1 = List.of("x", "y", "z");
        List<String> list2 = List.of("y", "w", "x");
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // 実行
        Object resultObj = resolver.resolveValue("{{ list1 | union(list2) }}", vars);

        // 検証: 重複排除かつ和集合 [x, y, z, w]
        assertTrue(resultObj instanceof List);
        List<String> result = (List<String>) resultObj;
        assertEquals(List.of("x", "y", "z", "w"), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSymmetricDifferenceEdgeCases() {
        // 準備
        List<Integer> list1 = List.of(10, 20, 30);
        List<Integer> list2 = List.of(20, 30, 40, 50);
        Map<String, Object> vars = Map.of("list1", list1, "list2", list2);

        // 実行
        Object resultObj = resolver.resolveValue("{{ list1 | symmetric_difference(list2) }}", vars);

        // 検証: 共通しない要素 [10, 40, 50]
        assertTrue(resultObj instanceof List);
        List<Integer> result = (List<Integer>) resultObj;
        assertEquals(List.of(10, 40, 50), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSetOperationsWithEmptyAndNullFallback() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("list1", List.of("a", "b"));
        vars.put("emptyList", Collections.emptyList());
        vars.put("nullVar", null);

        // 空リストとの difference -> 原本保持
        Object diffResult = resolver.resolveValue("{{ list1 | difference(emptyList) }}", vars);
        assertEquals(List.of("a", "b"), diffResult);

        // null との union -> 単一要素リストまたは空リストの統合
        Object unionResult = resolver.resolveValue("{{ list1 | union(nullVar) }}", vars);
        assertEquals(List.of("a", "b"), unionResult);
    }
}
