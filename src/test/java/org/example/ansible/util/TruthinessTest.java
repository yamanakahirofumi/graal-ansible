package org.example.ansible.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TruthinessTest {

    @Test
    void testIsTrue() {
        // Boolean
        assertTrue(Truthiness.isTrue(true));
        assertFalse(Truthiness.isTrue(false));

        // String
        assertTrue(Truthiness.isTrue("yes"));
        assertTrue(Truthiness.isTrue("True"));
        assertTrue(Truthiness.isTrue("on"));
        assertFalse(Truthiness.isTrue(""));
        assertFalse(Truthiness.isTrue(" "));
        assertFalse(Truthiness.isTrue("false"));
        assertFalse(Truthiness.isTrue("NO"));
        assertFalse(Truthiness.isTrue("off"));

        // Number
        assertTrue(Truthiness.isTrue(1));
        assertTrue(Truthiness.isTrue(0.1));
        assertFalse(Truthiness.isTrue(0));
        assertFalse(Truthiness.isTrue(0.0));

        // List
        assertTrue(Truthiness.isTrue(List.of(1)));
        assertFalse(Truthiness.isTrue(List.of()));

        // Map
        assertTrue(Truthiness.isTrue(Map.of("k", "v")));
        assertFalse(Truthiness.isTrue(Map.of()));

        // Null
        assertFalse(Truthiness.isTrue(null));
    }
}
