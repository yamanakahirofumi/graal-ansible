package org.example.ansible.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlattenFilterTest {

    private final FlattenFilter filter = new FlattenFilter();

    @Test
    @SuppressWarnings("unchecked")
    void testFlattenAllLevels() {
        List<Object> input = List.of(1, List.of(2, List.of(3, 4)), 5);
        Object result = filter.filter(input, null, new Object[]{}, Map.of());
        assertTrue(result instanceof List<?>);
        assertEquals(List.of(1, 2, 3, 4, 5), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlattenWithLevelsArg() {
        List<Object> input = List.of(1, List.of(2, List.of(3, 4)), 5);

        Object res1 = filter.filter(input, null, new Object[]{1}, Map.of());
        assertEquals(List.of(1, 2, List.of(3, 4), 5), res1);

        Object res1Kw = filter.filter(input, null, new Object[]{}, Map.of("levels", 1));
        assertEquals(List.of(1, 2, List.of(3, 4), 5), res1Kw);
    }

    @Test
    void testFlattenNonIterableInput() {
        Object nonIterable = "string_input";
        assertEquals("string_input", filter.filter(nonIterable, null, new Object[]{}, Map.of()));
    }

    @Test
    void testFlattenStringFallback() {
        List<Object> input = List.of(1, List.of(2, 3));
        Object res = filter.filter(input, null, new String[]{"1"});
        assertEquals(List.of(1, 2, 3), res);
    }
}
