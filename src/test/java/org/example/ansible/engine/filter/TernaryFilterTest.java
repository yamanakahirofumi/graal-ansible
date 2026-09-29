package org.example.ansible.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TernaryFilterTest {

    private final TernaryFilter filter = new TernaryFilter();

    @Test
    void testTernaryTrueConditionPreservesObjectTypes() {
        Object intResult = filter.filter(true, null, new Object[]{100, 200}, Map.of());
        assertEquals(100, intResult);
        assertInstanceOf(Integer.class, intResult);

        List<String> trueList = List.of("a", "b");
        List<String> falseList = List.of("c", "d");
        Object listResult = filter.filter("yes", null, new Object[]{trueList, falseList}, Map.of());
        assertEquals(trueList, listResult);

        Map<String, String> trueMap = Map.of("k", "v");
        Object mapResult = filter.filter(1, null, new Object[]{trueMap, Map.of()}, Map.of());
        assertEquals(trueMap, mapResult);
    }

    @Test
    void testTernaryFalseConditionPreservesObjectTypes() {
        Object intResult = filter.filter(false, null, new Object[]{100, 200}, Map.of());
        assertEquals(200, intResult);
        assertInstanceOf(Integer.class, intResult);

        Object boolResult = filter.filter("no", null, new Object[]{true, false}, Map.of());
        assertEquals(false, boolResult);
    }

    @Test
    void testTernaryNullConditionWithNullVal() {
        Object nullValResult = filter.filter(null, null, new Object[]{"true_val", "false_val", "null_val"}, Map.of());
        assertEquals("null_val", nullValResult);
    }

    @Test
    void testTernaryNullConditionWithoutNullVal() {
        Object falseValResult = filter.filter(null, null, new Object[]{"true_val", "false_val"}, Map.of());
        assertEquals("false_val", falseValResult);
    }

    @Test
    void testTernaryNoArgs() {
        assertEquals("input", filter.filter("input", null, new Object[]{}, Map.of()));
    }

    @Test
    void testTernaryStringFallback() {
        Object res = filter.filter(true, null, new String[]{"yes", "no"});
        assertEquals("yes", res);
    }
}
