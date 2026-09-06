package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MoreFiltersTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testB64EncodeAndDecode() {
        // b64encode
        assertEquals("aGVsbG8gd29ybGQ=", resolver.resolveValue("{{ 'hello world' | b64encode }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | b64encode }}", Map.of()));

        // b64decode
        assertEquals("hello world", resolver.resolveValue("{{ 'aGVsbG8gd29ybGQ=' | b64decode }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | b64decode }}", Map.of()));
        // Invalid base64 fallback to original string
        assertEquals("invalid_base64!!!", resolver.resolveValue("{{ 'invalid_base64!!!' | b64decode }}", Map.of()));
    }

    @Test
    void testQuote() {
        assertEquals("''", resolver.resolveValue("{{ '' | quote }}", Map.of()));
        assertEquals("'hello'", resolver.resolveValue("{{ 'hello' | quote }}", Map.of()));
        assertEquals("'hello '\\''world'\\'''", resolver.resolveValue("{{ \"hello 'world'\" | quote }}", Map.of()));
        assertEquals("''", resolver.resolveValue("{{ undefined_var | quote }}", Map.of()));
    }

    @Test
    void testBool() {
        assertEquals(true, resolver.resolveValue("{{ 'yes' | bool }}", Map.of()));
        assertEquals(true, resolver.resolveValue("{{ 'true' | bool }}", Map.of()));
        assertEquals(true, resolver.resolveValue("{{ 'on' | bool }}", Map.of()));
        assertEquals(true, resolver.resolveValue("{{ 1 | bool }}", Map.of()));

        assertEquals(false, resolver.resolveValue("{{ 'no' | bool }}", Map.of()));
        assertEquals(false, resolver.resolveValue("{{ 'false' | bool }}", Map.of()));
        assertEquals(false, resolver.resolveValue("{{ 'off' | bool }}", Map.of()));
        assertEquals(false, resolver.resolveValue("{{ 0 | bool }}", Map.of()));
        assertEquals(false, resolver.resolveValue("{{ undefined_var | bool }}", Map.of()));
    }

    @Test
    void testToJsonAndToYaml() {
        Map<String, Object> data = Map.of("name", "ansible", "version", 2);

        // to_json
        Object jsonResult = resolver.resolveValue("{{ data | to_json }}", Map.of("data", data));
        assertTrue(jsonResult instanceof String);
        String jsonStr = (String) jsonResult;
        assertTrue(jsonStr.contains("\"name\":\"ansible\""));
        assertTrue(jsonStr.contains("\"version\":2"));

        // to_yaml
        Object yamlResult = resolver.resolveValue("{{ data | to_yaml }}", Map.of("data", data));
        assertTrue(yamlResult instanceof String);
        String yamlStr = (String) yamlResult;
        assertTrue(yamlStr.contains("name: ansible"));
        assertTrue(yamlStr.contains("version: 2"));
    }

    @Test
    void testRegexReplace() {
        // Normal replacement
        assertEquals("graal-2.17", resolver.resolveValue("{{ 'ansible-2.17' | regex_replace('^ansible-', 'graal-') }}", Map.of()));
        // Deletion (default empty replacement)
        assertEquals("foobar", resolver.resolveValue("{{ 'foo123bar' | regex_replace('[0-9]+') }}", Map.of()));
        // Null or undefined
        assertEquals("", resolver.resolveValue("{{ undefined_var | regex_replace('[0-9]+') }}", Map.of()));
        // Invalid regex fallback
        assertEquals("test string", resolver.resolveValue("{{ 'test string' | regex_replace('[invalid', 'rep') }}", Map.of()));
    }

    @Test
    void testPathFiltersNullHandling() {
        assertEquals("", resolver.resolveValue("{{ undefined_var | basename }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | dirname }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | splitext }}", Map.of()));
        assertEquals("", resolver.resolveValue("{{ undefined_var | realpath }}", Map.of()));

        // Edge case splitext without extension
        Object result = resolver.resolveValue("{{ '/path/to/file' | splitext }}", Map.of());
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());
        assertEquals("/path/to/file", list.get(0));
        assertEquals("", list.get(1));
    }
}
