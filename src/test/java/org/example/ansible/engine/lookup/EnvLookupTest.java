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

        String template = "{{ lookup('env', 'NON_EXISTENT_VAR_ABC_987') }}";
        Object result = resolver.resolveValue(template, variables);
        assertEquals("", result);

        String queryTemplate = "{{ query('env', 'NON_EXISTENT_VAR_ABC_987') }}";
        Object queryResult = resolver.resolveValue(queryTemplate, variables);
        assertTrue(queryResult instanceof List);
        List<?> list = (List<?>) queryResult;
        assertEquals(1, list.size());
        assertEquals("", list.get(0));
    }

    @Test
    void testEnvLookupWithErrorsKwarg() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        String queryIgnore = "{{ query('env', 'NON_EXISTENT_VAR_ABC_987', errors='ignore') }}";
        Object resultIgnore = resolver.resolveValue(queryIgnore, variables);
        assertTrue(resultIgnore instanceof List);
        assertTrue(((List<?>) resultIgnore).isEmpty());

        String queryWarn = "{{ query('env', 'NON_EXISTENT_VAR_ABC_987', errors='warn') }}";
        Object resultWarn = resolver.resolveValue(queryWarn, variables);
        assertTrue(resultWarn instanceof List);
        assertTrue(((List<?>) resultWarn).isEmpty());
    }
}
