package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdditionalFiltersTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testMandatory() {
        assertEquals("value", resolver.resolveValue("{{ 'value' | mandatory }}", Map.of()));

        Exception exception = assertThrows(RuntimeException.class, () -> {
            resolver.resolveValue("{{ undefined_var | mandatory }}", Map.of());
        });
        assertTrue(exception.getMessage().contains("Mandatory variable is undefined or empty"));

        exception = assertThrows(RuntimeException.class, () -> {
            resolver.resolveValue("{{ '' | mandatory('Custom error message') }}", Map.of());
        });
        assertTrue(exception.getMessage().contains("Custom error message"));
    }

    @Test
    void testPathFilters() {
        // Forward slash case
        String path = "/path/to/file.txt";
        assertEquals("file.txt", resolver.resolveValue("{{ '" + path + "' | basename }}", Map.of()));
        assertEquals("/path/to", resolver.resolveValue("{{ '" + path + "' | dirname }}", Map.of()));

        Object splitextResult = resolver.resolveValue("{{ '" + path + "' | splitext }}", Map.of());
        assertTrue(splitextResult instanceof List);
        List<?> list = (List<?>) splitextResult;
        assertEquals(2, list.size());
        assertEquals("/path/to/file", list.get(0));
        assertEquals(".txt", list.get(1));

        // Backslash case (Windows path emulation)
        String winPath = "C:\\path\\to\\file.txt";
        assertEquals("file.txt", resolver.resolveValue("{{ '" + winPath.replace("\\", "\\\\") + "' | basename }}", Map.of()));
        assertEquals("C:\\path\\to", resolver.resolveValue("{{ '" + winPath.replace("\\", "\\\\") + "' | dirname }}", Map.of()));

        // Edge case: single slash
        assertEquals("/", resolver.resolveValue("{{ '/' | dirname }}", Map.of()));
        assertEquals("\\", resolver.resolveValue("{{ '\\\\' | dirname }}", Map.of()));

        // Realpath should return absolute path with normalized separators
        String realpathResult = (String) resolver.resolveValue("{{ 'pom.xml' | realpath }}", Map.of());
        assertTrue(new File(realpathResult).isAbsolute());
        assertFalse(realpathResult.contains("\\"));
    }

    @Test
    void testTernary() {
        assertEquals("yes", resolver.resolveValue("{{ true | ternary('yes', 'no') }}", Map.of()));
        assertEquals("no", resolver.resolveValue("{{ false | ternary('yes', 'no') }}", Map.of()));
        assertEquals("none", resolver.resolveValue("{{ undefined_var | ternary('yes', 'no', 'none') }}", Map.of()));

        // Truthiness test
        assertEquals("yes", resolver.resolveValue("{{ 'non-empty' | ternary('yes', 'no') }}", Map.of()));
        assertEquals("no", resolver.resolveValue("{{ '' | ternary('yes', 'no') }}", Map.of()));
    }

    @Test
    void testFlatten() {
        List<Object> nested = List.of(1, List.of(2, List.of(3, 4)), 5);
        Map<String, Object> vars = Map.of("nested", nested);

        Object result = resolver.resolveValue("{{ nested | flatten }}", vars);
        assertTrue(result instanceof List);
        assertEquals(List.of(1, 2, 3, 4, 5), result);
    }

    @Test
    void testItems2Dict() {
        List<Map<String, Object>> input = List.of(
                Map.of("key", "a", "value", 1),
                Map.of("key", "b", "value", 2)
        );
        Object result = resolver.resolveValue("{{ input | items2dict }}", Map.of("input", input));
        assertEquals(Map.of("a", 1, "b", 2), result);

        // Custom key/value names
        List<Map<String, Object>> inputCustom = List.of(
                Map.of("name", "foo", "val", "bar"),
                Map.of("name", "baz", "val", "qux")
        );
        Object resultCustom = resolver.resolveValue("{{ input | items2dict(key_name='name', value_name='val') }}", Map.of("input", inputCustom));
        assertEquals(Map.of("foo", "bar", "baz", "qux"), resultCustom);
    }

    @Test
    void testUnique() {
        List<Integer> input = List.of(1, 2, 2, 3, 1, 4);
        Object result = resolver.resolveValue("{{ input | unique }}", Map.of("input", input));
        assertEquals(List.of(1, 2, 3, 4), result);

        // Unique with attribute
        List<Map<String, Object>> inputAttr = List.of(
                Map.of("id", 1, "name", "alice"),
                Map.of("id", 2, "name", "bob"),
                Map.of("id", 1, "name", "charlie")
        );
        Object resultAttr = resolver.resolveValue("{{ input | unique(attribute='id') }}", Map.of("input", inputAttr));
        assertTrue(resultAttr instanceof List);
        List<?> list = (List<?>) resultAttr;
        assertEquals(2, list.size());
        assertEquals("alice", ((Map<?, ?>) list.get(0)).get("name"));
        assertEquals("bob", ((Map<?, ?>) list.get(1)).get("name"));
    }

    @Test
    void testUrlencode() {
        assertEquals("foo%20bar", resolver.resolveValue("{{ 'foo bar' | urlencode }}", Map.of()));
        assertEquals("a%3Db%26c", resolver.resolveValue("{{ 'a=b&c' | urlencode }}", Map.of()));

        Map<String, Object> map = Map.of("a", 1, "b", "c d");
        Object result = resolver.resolveValue("{{ map | urlencode }}", Map.of("map", map));
        assertTrue(result instanceof String);
        String str = (String) result;
        // Map order is not guaranteed, check both possibilities
        assertTrue(str.equals("a=1&b=c%20d") || str.equals("b=c%20d&a=1"));
    }
}
