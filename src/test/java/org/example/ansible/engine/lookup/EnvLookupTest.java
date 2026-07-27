package org.example.ansible.engine.lookup;

import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EnvLookupTest {

    @Test
    void testEnvLookupExisting() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        // PATH is standard across all typical build and execution environments
        String template = "{{ lookup('env', 'PATH') }}";
        Object result = resolver.resolveValue(template, variables);
        assertNotNull(result);
        assertFalse(result.toString().isEmpty());
    }

    @Test
    void testEnvLookupNonExistingWithDefault() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        String template = "{{ lookup('env', 'NON_EXISTENT_VAR_ABC_987', default='my_fallback') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("my_fallback", result);
    }

    @Test
    void testEnvLookupQueryNonExistingWithDefault() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        String template = "{{ query('env', 'NON_EXISTENT_VAR_ABC_987', default='my_fallback') }}";
        Object result = resolver.resolveValue(template, variables);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertEquals(1, list.size());
        assertEquals("my_fallback", list.get(0));
    }

    @Test
    void testEnvLookupNonExistingWithoutDefault() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        String template = "{{ query('env', 'NON_EXISTENT_VAR_ABC_987') }}";
        Object result = resolver.resolveValue(template, variables);
        assertTrue(result instanceof List);
        List<?> list = (List<?>) result;
        assertTrue(list.isEmpty());
    }
}
