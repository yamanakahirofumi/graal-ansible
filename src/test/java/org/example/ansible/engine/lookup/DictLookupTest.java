package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DictLookupTest {

    @Test
    @SuppressWarnings("unchecked")
    void testDictLookupQuery() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> myDict = new HashMap<>();
        myDict.put("key1", "val1");
        myDict.put("key2", "val2");
        variables.put("my_dict", myDict);

        String template = "{{ query('dict', my_dict) }}";
        Object result = resolver.resolveValue(template, variables);

        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(2, list.size());

        Map<String, Object> item1 = (Map<String, Object>) list.get(0);
        Map<String, Object> item2 = (Map<String, Object>) list.get(1);

        assertTrue(item1.containsKey("key"));
        assertTrue(item1.containsKey("value"));

        // Check values matching keys
        Object val1 = item1.get("key").equals("key1") ? item1.get("value") : item2.get("value");
        Object val2 = item1.get("key").equals("key2") ? item1.get("value") : item2.get("value");
        assertEquals("val1", val1);
        assertEquals("val2", val2);
    }

    @Test
    void testDictLookupString() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> myDict = new HashMap<>();
        myDict.put("key1", "val1");
        variables.put("my_dict", myDict);

        String template = "{{ lookup('dict', my_dict) }}";
        Object result = resolver.resolveValue(template, variables);
        // Returns string format of the list items
        assertNotNull(result);
        assertTrue(result.toString().contains("key=key1"));
        assertTrue(result.toString().contains("value=val1"));
    }

    @Test
    void testDictLookupInvalidType() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        variables.put("invalid_var", "this is a string, not a map");

        String template = "{{ query('dict', invalid_var) }}";
        assertThrows(RuntimeException.class, () -> resolver.resolveValue(template, variables));
    }
}
