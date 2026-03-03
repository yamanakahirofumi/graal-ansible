package org.example.ansible.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Dict2ItemsFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void testDict2ItemsSimple() {
        Dict2ItemsFilter filter = new Dict2ItemsFilter();
        Map<String, Object> input = Map.of("a", 1, "b", 2);

        Object result = filter.filter(input, null);

        assertTrue(result instanceof List);
        List<Map<String, Object>> list = (List<Map<String, Object>>) result;
        assertEquals(2, list.size());

        boolean foundA = false;
        boolean foundB = false;
        for (Map<String, Object> entry : list) {
            if (entry.get("key").equals("a")) {
                assertEquals(1, entry.get("value"));
                foundA = true;
            } else if (entry.get("key").equals("b")) {
                assertEquals(2, entry.get("value"));
                foundB = true;
            }
        }
        assertTrue(foundA);
        assertTrue(foundB);
    }
}
