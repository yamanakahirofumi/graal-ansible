package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlencodeFilterTest {

    @Test
    void testUrlencodeString() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> vars = Map.of("my_str", "hello world / test");

        Object result = resolver.resolveValue("{{ my_str | urlencode }}", vars);
        assertEquals("hello%20world%20%2F%20test", result);
    }

    @Test
    void testUrlencodeMap() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> vars = Map.of("my_map", Map.of("key1", "val 1", "key2", "val/2"));

        Object result = resolver.resolveValue("{{ my_map | urlencode }}", vars);
        String resultStr = (String) result;
        assertTrue(resultStr.contains("key1=val%201"));
        assertTrue(resultStr.contains("key2=val%2F2"));
        assertTrue(resultStr.contains("&"));
    }
}
