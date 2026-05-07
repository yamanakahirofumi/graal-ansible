package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilterExtensionTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testRegexReplace() {
        String template = "{{ 'hello world' | regex_replace('world', 'ansible') }}";
        Object result = resolver.resolveValue(template, Map.of());
        assertEquals("hello ansible", result);

        template = "{{ 'foo123bar' | regex_replace('[0-9]+', '') }}";
        result = resolver.resolveValue(template, Map.of());
        assertEquals("foobar", result);
    }

    @Test
    void testQuote() {
        String template = "{{ 'hello' | quote }}";
        Object result = resolver.resolveValue(template, Map.of());
        assertEquals("'hello'", result);

        template = "{{ \"it's dynamic\" | quote }}";
        result = resolver.resolveValue(template, Map.of());
        assertEquals("'it'\\''s dynamic'", result);
    }

    @Test
    void testBase64() {
        String template = "{{ 'ansible' | b64encode }}";
        Object result = resolver.resolveValue(template, Map.of());
        assertEquals("YW5zaWJsZQ==", result);

        template = "{{ 'YW5zaWJsZQ==' | b64decode }}";
        result = resolver.resolveValue(template, Map.of());
        assertEquals("ansible", result);
    }
}
