package org.example.ansible.engine.lookup;

import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import org.example.ansible.engine.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VarsLookupTest {

    @Test
    void testVarsLookup() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        variables.put("my_var", "hello");
        variables.put("other_var", "world");

        // Test single variable
        String template1 = "{{ lookup('vars', 'my_var') }}";
        assertEquals("hello", resolver.resolveValue(template1, variables));

        // Test multiple variables (returns comma-separated by default for lookup)
        String template2 = "{{ lookup('vars', 'my_var', 'other_var') }}";
        assertEquals("hello,world", resolver.resolveValue(template2, variables));
    }

    @Test
    void testVarsLookupNotFound() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();

        String template = "{{ lookup('vars', 'non_existent') }}";
        assertThrows(RuntimeException.class, () -> resolver.resolveValue(template, variables));
    }

    @Test
    void testVarsLookupWithDefault() {
        VariableResolver resolver = new VariableResolver();
        Map<String, Object> variables = new HashMap<>();
        variables.put("my_var", "hello");

        // Test non-existent variable with default argument
        String template1 = "{{ lookup('vars', 'non_existent', default='fallback') }}";
        assertEquals("fallback", resolver.resolveValue(template1, variables));

        // Test existing variable with default argument (should return variable value)
        String template2 = "{{ lookup('vars', 'my_var', default='fallback') }}";
        assertEquals("hello", resolver.resolveValue(template2, variables));
    }
}
