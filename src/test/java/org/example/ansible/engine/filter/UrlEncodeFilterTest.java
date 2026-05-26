package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UrlEncodeFilterTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testUrlEncodeString() {
        assertEquals("foo%20bar", resolver.resolveValue("{{ 'foo bar' | urlencode }}", Map.of()));
        assertEquals("foo%2Fbar", resolver.resolveValue("{{ 'foo/bar' | urlencode }}", Map.of()));
        assertEquals("foo~bar", resolver.resolveValue("{{ 'foo~bar' | urlencode }}", Map.of()));
        assertEquals("foo%2Abar", resolver.resolveValue("{{ 'foo*bar' | urlencode }}", Map.of()));
    }

    @Test
    void testUrlEncodeMap() {
        Map<String, Object> input = Map.of("a", "foo bar", "b", "baz/qux");
        String result = (String) resolver.resolveValue("{{ input | urlencode }}", Map.of("input", input));

        // Map ordering might vary, so check both possibilities or contains
        assertTrue(result.equals("a=foo%20bar&b=baz%2Fqux") || result.equals("b=baz%2Fqux&a=foo%20bar"));
    }

    @Test
    void testUrlEncodeNull() {
        assertEquals("", resolver.resolveValue("{{ null_var | urlencode }}", Map.of()));
    }
}
