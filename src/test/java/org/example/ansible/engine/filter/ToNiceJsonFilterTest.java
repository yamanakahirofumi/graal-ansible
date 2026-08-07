package org.example.ansible.engine.filter;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToNiceJsonFilterTest {

    private final VariableResolver resolver = new VariableResolver();

    @Test
    void testToNiceJsonDefault() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("z", "last");
        data.put("a", "first");

        String result = (String) resolver.resolveValue("{{ data | to_nice_json }}", Map.of("data", data));
        assertNotNull(result);

        // Default is sorted keys and indent=4.
        // Let's check that 'a' comes before 'z'
        int indexA = result.indexOf("\"a\"");
        int indexZ = result.indexOf("\"z\"");
        assertTrue(indexA < indexZ, "Keys should be sorted alphabetically");

        // Default indent should be 4 spaces
        assertTrue(result.contains("    \"a\" : \"first\""), "Indentation should be 4 spaces");
    }

    @Test
    void testToNiceJsonCustomIndent() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("a", 1);

        // Test positional indent=2
        String resultPositional = (String) resolver.resolveValue("{{ data | to_nice_json(2) }}", Map.of("data", data));
        assertTrue(resultPositional.contains("  \"a\" : 1"), "Indentation should be 2 spaces");

        // Test keyword indent=3
        String resultKeyword = (String) resolver.resolveValue("{{ data | to_nice_json(indent=3) }}", Map.of("data", data));
        assertTrue(resultKeyword.contains("   \"a\" : 1"), "Indentation should be 3 spaces");
    }

    @Test
    void testToNiceJsonUnsorted() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("z", 2);
        data.put("a", 1);

        // Test sort_keys=false positional
        String resultPositional = (String) resolver.resolveValue("{{ data | to_nice_json(4, false) }}", Map.of("data", data));
        int indexZ = resultPositional.indexOf("\"z\"");
        int indexA = resultPositional.indexOf("\"a\"");
        assertTrue(indexZ < indexA, "Keys should remain unsorted (original insertion order)");

        // Test sort_keys=false keyword
        String resultKeyword = (String) resolver.resolveValue("{{ data | to_nice_json(sort_keys=false) }}", Map.of("data", data));
        int indexZ2 = resultKeyword.indexOf("\"z\"");
        int indexA2 = resultKeyword.indexOf("\"a\"");
        assertTrue(indexZ2 < indexA2, "Keys should remain unsorted (original insertion order)");
    }
}
